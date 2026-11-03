package services.crunch.desklimits

import services.crunch.deskrecs.DeskRecs
import services.crunch.CalculationControl
import services.{ WorkloadProcessors, WorkloadProcessorsProvider }
import uk.gov.homeoffice.drt.egates.{ Desk, EgateBanksUpdates }
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.time.{ LocalDate, SDate }

import scala.collection.immutable.{ Map, NumericRange }
import scala.concurrent.{ ExecutionContext, Future }

trait QueueCapacityProvider {
  def configuredMaximums: Option[IndexedSeq[Int]] = None

  def capacityForPeriod(timeRange: NumericRange[Long]): Future[WorkloadProcessorsProvider]

  def capacityForPeriodControlled(
      timeRange: NumericRange[Long],
      control: CalculationControl
  )(implicit cpuEc: ExecutionContext): Future[WorkloadProcessorsProvider] = {
    control.check()
    capacityForPeriod(timeRange).map { processors =>
      control.check()
      processors
    }
  }
}

object EmptyCapacityProvider extends QueueCapacityProvider {
  override def capacityForPeriod(timeRange: NumericRange[Long]): Future[WorkloadProcessorsProvider] =
    Future.successful(WorkloadProcessorsProvider(timeRange.map(_ => WorkloadProcessors(Seq()))))
}

case class DeskCapacityProvider(maxPerHour: IndexedSeq[Int]) extends QueueCapacityProvider {
  assert(maxPerHour.length == 24, s"There must be 24 hours worth of max desks defined. ${maxPerHour.length} found")

  override def capacityForPeriod(timeRange: NumericRange[Long]): Future[WorkloadProcessorsProvider] = {
    Future.successful(WorkloadProcessorsProvider(DeskRecs.desksForMillis(timeRange, maxPerHour).map(x =>
      WorkloadProcessors(Seq.fill(x)(Desk))
    )))
  }

  override def capacityForPeriodControlled(
      timeRange: NumericRange[Long],
      control: CalculationControl
  )(implicit cpuEc: ExecutionContext): Future[WorkloadProcessorsProvider] =
    Future {
      WorkloadProcessorsProvider(timeRange.map { minute =>
        control.check()
        val count = DeskRecs.desksForHourOfDayInUKLocalTime(minute, maxPerHour)
        WorkloadProcessors(Vector.fill(count) {
          control.check()
          Desk
        })
      })
    }
}

case class EgatesCapacityProvider(
    egatesProvider: () => Future[EgateBanksUpdates],
    override val configuredMaximums: Option[IndexedSeq[Int]] = None
)(implicit ec: ExecutionContext)
    extends QueueCapacityProvider {
  override def capacityForPeriod(timeRange: NumericRange[Long]): Future[WorkloadProcessorsProvider] =
    egatesProvider().map(updates => WorkloadProcessorsProvider(updates.forPeriod(timeRange).map(WorkloadProcessors(_))))

  override def capacityForPeriodControlled(
      timeRange: NumericRange[Long],
      control: CalculationControl
  )(implicit cpuEc: ExecutionContext): Future[WorkloadProcessorsProvider] = {
    control.check()
    egatesProvider().map { updates =>
      control.check()
      WorkloadProcessorsProvider(updates.forPeriod(timeRange).map { banks =>
        control.check()
        WorkloadProcessors(banks)
      })
    }(cpuEc)
  }
}

trait ConfiguredTerminalDeskLimitsLike extends TerminalDeskLimitsLike {
  val capacityByQueue: Map[Queue, QueueCapacityProvider]

  def sharedStaffByMinute: Option[IndexedSeq[Int]] = None
}

trait TerminalDeskLimitsLike {
  def calculationControl: CalculationControl = CalculationControl.Unlimited

  val minDesksByQueue24Hrs: LocalDate => Map[Queue, IndexedSeq[Int]]
  val paxForQueue: (NumericRange[Long], Queue) => Future[Seq[Int]]

  def deskLimitsForMinutes(
      minuteMillis: NumericRange[Long],
      queue: Queue,
      allocatedDesks: Map[Queue, List[Int]]
  )(implicit ec: ExecutionContext): Future[(Iterable[Int], WorkloadProcessorsProvider)] = {
    calculationControl.check()
    val date = SDate(minuteMillis.min).toLocalDate

    maxDesksForMinutes(minuteMillis, queue, allocatedDesks).flatMap { processorProvider =>
      paxForQueue(minuteMillis, queue).map { paxByMinute =>
        val minDesksByMinute = DeskRecs
          .desksForMillis(minuteMillis, minDesksByQueue24Hrs(date).getOrElse(queue, IndexedSeq.fill(24)(0)))

        val minDesksForWorkload = DeskRecs.minDesksForWorkload(minDesksByMinute, paxByMinute)

        val minDesks = minDesksForWorkload
          .toList.zip(processorProvider.processorsByMinute)
          .map { case (min, max) =>
            Math.min(min, max.maxCapacity)
          }
        (minDesks, processorProvider)
      }
    }
  }

  def maxDesksForMinutes(
      minuteMillis: NumericRange[Long],
      queue: Queue,
      existingAllocations: Map[Queue, List[Int]]
  ): Future[WorkloadProcessorsProvider]
}
