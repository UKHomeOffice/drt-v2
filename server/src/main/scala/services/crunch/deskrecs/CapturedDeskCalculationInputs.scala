package services.crunch.deskrecs

import services.WorkloadProcessorsProvider
import services.crunch.{ CalculationControl, CalculationExecutionContext }
import services.crunch.desklimits.{ ConfiguredTerminalDeskLimitsLike, QueueCapacityProvider }
import services.crunch.desklimits.fixed.FixedTerminalDeskLimits
import services.crunch.desklimits.flexed.{
  BalancedDeskLimitsSnapshot,
  FlexedTerminalDeskLimits,
  FlexedTerminalDeskLimitsFromAvailableStaff,
  FlexedTerminalDeskLimitsLike
}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.time.{ LocalDate, SDate }

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.NumericRange
import scala.concurrent.{ ExecutionContext, Future }

final class CapturedDeskCalculationInputs private (
    val minuteMillis: NumericRange[Long],
    val passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
    original: ConfiguredTerminalDeskLimitsLike,
    originalSla: (LocalDate, Queue) => Future[Int],
    manualDeskCapacity: Option[Int]
) {
  private val date: LocalDate = SDate(minuteMillis.head).toLocalDate
  private val slaReads = new ConcurrentHashMap[Queue, Future[Int]]()
  private val capacityReads = new ConcurrentHashMap[Queue, Future[WorkloadProcessorsProvider]]()
  private val snapshotReads = new ConcurrentHashMap[CalculationControl, Future[BalancedDeskLimitsSnapshot]]()
  private val completedSnapshot = new AtomicReference[Option[BalancedDeskLimitsSnapshot]](None)
  private lazy val hourlyMinimums: Map[Queue, IndexedSeq[Int]] =
    original.minDesksByQueue24Hrs(date).map { case (queue, values) => queue -> values.toVector }
  private val staff: Option[IndexedSeq[Int]] = original.sharedStaffByMinute.map(_.toVector)

  def sla(queue: Queue, control: CalculationControl)(implicit ec: ExecutionContext): Future[Int] =
    slaReads.computeIfAbsent(
      queue,
      q =>
        CalculationExecutionContext.trackInput(Future.unit.flatMap { _ =>
          control.check()
          originalSla(date, q)
        })
    )

  private def capacity(
      queue: Queue,
      control: CalculationControl
  )(implicit ec: ExecutionContext): Future[WorkloadProcessorsProvider] =
    capacityReads.computeIfAbsent(
      queue,
      q =>
        CalculationExecutionContext.trackInput(Future.unit.flatMap { _ =>
          control.check()
          require(original.capacityByQueue.contains(q), s"Missing captured capacity provider for $q")
          original.capacityByQueue(q).capacityForPeriodControlled(minuteMillis, control)
        })
    )

  def prepareLegacy(
      priority: List[Queue],
      control: CalculationControl
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val queues = priority.filter(passengers.contains).distinct
    val minimumsReady = Future {
      control.check()
      if (queues.nonEmpty) hourlyMinimums
      ()
    }
    val slaReady = Future.traverse(queues)(queue => sla(queue, control))
    val legacyCapacityQueues = original match {
      case flexed: FlexedTerminalDeskLimitsLike => queues.filterNot(flexed.flexedQueues.contains)
      case _                                    => queues
    }
    val capacityReady =
      Future.traverse(legacyCapacityQueues.filter(original.capacityByQueue.contains))(queue => capacity(queue, control))
    for {
      _ <- minimumsReady
      _ <- slaReady
      _ <- capacityReady
    } yield {
      control.check()
      ()
    }
  }

  def frozenLimits(control: CalculationControl)(implicit ec: ExecutionContext): ConfiguredTerminalDeskLimitsLike = {
    control.check()
    val minimums = hourlyMinimums
    val capacities = original.capacityByQueue.keys.map { queue =>
      queue -> new QueueCapacityProvider {
        override val configuredMaximums: Option[IndexedSeq[Int]] = original.capacityByQueue(queue).configuredMaximums

        override def capacityForPeriod(range: NumericRange[Long]): Future[WorkloadProcessorsProvider] = {
          require(range == minuteMillis, "Captured capacity cannot be used for another window")
          capacity(queue, control)
        }
      }
    }.toMap
    val counts: (NumericRange[Long], Queue) => Future[Seq[Int]] = (range, queue) => {
      require(range == minuteMillis, "Captured passenger counts cannot be used for another window")
      Future.successful(passengers(queue).map { values =>
        control.check()
        values.size
      })
    }
    original match {
      case _: FixedTerminalDeskLimits =>
        FixedTerminalDeskLimits(_ => minimums, capacities, counts, control)
      case flexed: FlexedTerminalDeskLimitsLike => staff match {
          case Some(available) =>
            FlexedTerminalDeskLimitsFromAvailableStaff(
              available.toList,
              flexed.terminalDesks,
              flexed.flexedQueues,
              _ => minimums,
              capacities,
              counts,
              control
            )
          case None =>
            FlexedTerminalDeskLimits(
              flexed.terminalDesks,
              flexed.flexedQueues,
              _ => minimums,
              capacities,
              counts,
              control
            )
        }
      case _ => throw new IllegalArgumentException("Unsupported configured desk limits")
    }
  }

  def allSlas(control: CalculationControl)(implicit ec: ExecutionContext): Future[Map[Queue, Int]] =
    Future.traverse(passengers.keys.toSeq.sortBy(_.toString)) { queue =>
      sla(queue, control).map { value =>
        control.check()
        queue -> value
      }
    }.map(_.toMap)

  def balancedSnapshot(control: CalculationControl)(implicit ec: ExecutionContext): Future[BalancedDeskLimitsSnapshot] =
    completedSnapshot.get() match {
      case Some(snapshot) => Future {
          control.check()
          snapshot
        }
      case None =>
        snapshotReads.computeIfAbsent(
          control,
          _ =>
            Future.unit.flatMap { _ =>
              control.check()
              BalancedDeskLimitsSnapshot.materialise(
                minuteMillis,
                passengers,
                frozenLimits(control),
                control,
                manualDeskCapacity
              )
            }.map { snapshot =>
              control.check()
              completedSnapshot.compareAndSet(None, Some(snapshot))
              snapshot
            }
        )
    }
}

object CapturedDeskCalculationInputs {
  def capture(
      minuteMillis: NumericRange[Long],
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      limits: ConfiguredTerminalDeskLimitsLike,
      sla: (LocalDate, Queue) => Future[Int],
      control: CalculationControl = CalculationControl.Unlimited,
      manualDeskCapacity: Option[Int] = None
  )(implicit ec: ExecutionContext): Future[CapturedDeskCalculationInputs] =
    Future {
      control.check()
      require(minuteMillis.nonEmpty, "Captured calculation requires a non-empty window")
      require(minuteMillis.step == 60000L, "Captured calculation requires consecutive minutes")
      val frozen = passengers.map { case (queue, values) =>
        require(values.size == minuteMillis.size, "Captured passengers must match the window")
        queue -> values.map(_.iterator.map { value =>
          control.check()
          value
        }.toVector)
      }
      new CapturedDeskCalculationInputs(minuteMillis, frozen, limits, sla, manualDeskCapacity)
    }
}
