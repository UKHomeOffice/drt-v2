package services.crunch.desklimits

import drt.shared.MilliTimes
import services.{OptimiserWithFlexibleProcessors, WorkloadProcessors, WorkloadProcessorsProvider}
import services.crunch.deskrecs.DeskRecs
import uk.gov.homeoffice.drt.egates.{Desk, EgateBank, EgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.Queues.Queue

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}

trait QueueCapacityProvider {
  def capacityForPeriod(timeRange: NumericRange[Long]): Future[WorkloadProcessorsProvider]
}

object EmptyCapacityProvider extends QueueCapacityProvider {
  override def capacityForPeriod(timeRange: NumericRange[Long]): Future[WorkloadProcessorsProvider] =
    Future.successful(WorkloadProcessorsProvider(timeRange.map(_ => WorkloadProcessors(Seq()))))
}

case class DeskCapacityProvider(maxPerHour: IndexedSeq[Int])
                               (implicit ec: ExecutionContext) extends QueueCapacityProvider {
  assert(maxPerHour.length == 24, s"There must be 24 hours worth of max desks defined. ${maxPerHour.length} found")

  override def capacityForPeriod(timeRange: NumericRange[Long]): Future[WorkloadProcessorsProvider] = {
    Future.successful(WorkloadProcessorsProvider(DeskRecs.desksForMillis(timeRange, maxPerHour).map(x => WorkloadProcessors(Seq.fill(x)(Desk)))))
  }
}

case class EgatesCapacityProvider(egatesProvider: () => Future[EgateBanksUpdates])
                                 (implicit ec: ExecutionContext) extends QueueCapacityProvider {
  override def capacityForPeriod(timeRange: NumericRange[Long]): Future[WorkloadProcessorsProvider] =
    egatesProvider().map(updates => WorkloadProcessorsProvider(updates.forPeriod(timeRange).map(WorkloadProcessors(_))))
}

trait TerminalDeskLimitsLike_ {
  val minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]

  def deskLimitsForMinutes(minuteMillis: NumericRange[Long], queue: Queue, allocatedDesks: Map[Queue, List[Int]])
                          (implicit ec: ExecutionContext): Future[(Iterable[Int], Iterable[Int])] = {
    maxDesksForMinutes(minuteMillis, queue, allocatedDesks).map { maxDesks =>
      val minDesks = DeskRecs
        .desksForMillis(minuteMillis, minDesksByQueue24Hrs(queue))
        .toList.zip(maxDesks)
        .map { case (min, max) =>
          Math.min(min, max)
        }
      (minDesks, maxDesks)
    }
  }

  def maxDesksForMinutes(minuteMillis: NumericRange[Long],
                         queue: Queue,
                         existingAllocations: Map[Queue, List[Int]]): Future[Iterable[Int]]
}

trait TerminalDeskLimitsLike {
  val minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]

  def deskLimitsForMinutes(minuteMillis: NumericRange[Long], queue: Queue, allocatedDesks: Map[Queue, List[Int]])
                          (implicit ec: ExecutionContext): Future[(Iterable[Int], WorkloadProcessorsProvider)] = {
    maxDesksForMinutes(minuteMillis, queue, allocatedDesks).map { processorProvider =>
      val minDesks = DeskRecs
        .desksForMillis(minuteMillis, minDesksByQueue24Hrs(queue))
        .toList.zip(processorProvider.processorsByMinute)
        .map { case (min, max) =>
          Math.min(min, max.processors.map(_.maxCapacity).sum)
        }
      (minDesks, processorProvider)
    }
  }

  def maxDesksForMinutes(minuteMillis: NumericRange[Long],
                         queue: Queue,
                         existingAllocations: Map[Queue, List[Int]]): Future[WorkloadProcessorsProvider]
}
