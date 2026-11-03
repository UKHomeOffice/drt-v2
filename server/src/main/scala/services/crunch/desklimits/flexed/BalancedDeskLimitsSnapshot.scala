package services.crunch.desklimits.flexed

import services.WorkloadProcessorsProvider
import services.WorkloadProcessors
import services.crunch.desklimits.ConfiguredTerminalDeskLimitsLike
import services.crunch.deskrecs.DeskRecs
import services.crunch.CalculationControl
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, Queue }
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.immutable.NumericRange
import scala.concurrent.{ ExecutionContext, Future }

case class BalancedDeskLimitsSnapshot(
    minuteMillis: IndexedSeq[Long],
    terminalDesks: Int,
    availableStaff: Option[IndexedSeq[Int]],
    configuredMinimums: Map[Queue, IndexedSeq[Int]],
    minimums: Map[Queue, IndexedSeq[Int]],
    processorsByQueue: Map[Queue, WorkloadProcessorsProvider]
)

object BalancedDeskLimitsSnapshot {
  def materialise(
      minuteMillis: NumericRange[Long],
      passengersByQueue: Map[Queue, IndexedSeq[Iterable[Double]]],
      limits: ConfiguredTerminalDeskLimitsLike,
      control: CalculationControl = CalculationControl.Unlimited,
      manualDeskCapacity: Option[Int] = None
  )(implicit ec: ExecutionContext): Future[BalancedDeskLimitsSnapshot] =
    Future {
      control.check()
      require(minuteMillis.nonEmpty, "Balanced calculation requires a non-empty minute range")
      require(
        minuteMillis.step == 60000L && minuteMillis.forall(m => Math.floorMod(m, 60000L) == 0),
        "Balanced calculation requires aligned, consecutive minutes"
      )
      val terminalDesks = manualDeskCapacity.orElse(limits match {
        case flexed: FlexedTerminalDeskLimitsLike => Some(flexed.terminalDesks)
        case _                                    => None
      }).getOrElse(throw new IllegalArgumentException("Balanced calculation requires manual terminal desk capacity"))
      require(terminalDesks >= 0, "Terminal desk capacity must be non-negative")
      passengersByQueue.values.foreach { minutes =>
        control.check()
        require(minutes.size == minuteMillis.size, "Passenger schedule must match the calculation window")
        require(
          minutes.forall(_.forall { load =>
            control.check()
            load.isFinite && load >= 0 && Math.round(load * 60) <= Int.MaxValue.toLong
          }),
          "Passenger processing loads must be finite, non-negative and representable in seconds"
        )
      }
      val staff = limits.sharedStaffByMinute
      staff.foreach { minutes =>
        require(minutes.size == minuteMillis.size, "Staff schedule must match the calculation window")
        require(minutes.forall(_ >= 0), "Available staff must be non-negative")
      }
      val configuredForDate = limits.minDesksByQueue24Hrs(SDate(minuteMillis.head).toLocalDate)
      val configured = passengersByQueue.keys.map { queue =>
        control.check()
        val hourly = configuredForDate.getOrElse(queue, IndexedSeq.fill(24)(0))
        require(hourly.size == 24 && hourly.forall(_ >= 0), "Queue minimums require 24 non-negative hourly values")
        queue -> DeskRecs.desksForMillis(minuteMillis, hourly, control)
      }.toMap
      val minimums = configured.map { case (queue, values) =>
        control.check()
        queue -> DeskRecs.minDesksForWorkload(values, passengersByQueue(queue).map(_.size), control)
          .take(minuteMillis.size).toIndexedSeq
      }
      (terminalDesks, staff, configured, minimums)
    }.flatMap { case (terminalDesks, staff, configured, minimums) =>
      Future.traverse(passengersByQueue.keys.toSeq.sortBy(_.toString)) { queue =>
        control.check()
        require(limits.capacityByQueue.contains(queue), s"Missing balanced capacity provider for $queue")
        val provider = limits.capacityByQueue(queue)
        provider.capacityForPeriodControlled(minuteMillis, control).map { processors =>
          control.check()
          require(processors.minutes == minuteMillis.size, "Processor schedule must match the calculation window")
          require(
            processors.processorsByMinute.forall(_.processors.forall(_.openCount >= 0)),
            "Processor capacity must be non-negative"
          )
          val bounded =
            // The date-effective bank profiles, not hourly desk metadata, define usable eGate capacity.
            if (queue == EGate) processors
            else {
              val maximums = provider.configuredMaximums.map { hourly =>
                require(
                  hourly.size == 24 && hourly.forall(_ >= 0),
                  "Queue maxima require 24 non-negative hourly values"
                )
                DeskRecs.desksForMillis(minuteMillis, hourly, control)
              }
              maximums.fold(processors) { caps =>
                WorkloadProcessorsProvider(processors.processorsByMinute.zip(caps).map { case (minute, cap) =>
                  control.check()
                  WorkloadProcessors(minute.processors.take(cap))
                })
              }
            }
          queue -> bounded
        }
      }.map { processors =>
        control.check()
        BalancedDeskLimitsSnapshot(
          minuteMillis.toIndexedSeq,
          terminalDesks,
          staff,
          configured,
          minimums,
          processors.toMap
        )
      }
    }
}
