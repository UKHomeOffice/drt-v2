package services.crunch.desklimits.flexed

import services.WorkloadProcessorsProvider
import services.crunch.desklimits.{EmptyCapacityProvider, QueueCapacityProvider, TerminalDeskLimitsLike}
import services.crunch.deskrecs.DeskRecs
import services.graphstages.Crunch.reduceIterables
import uk.gov.homeoffice.drt.egates.Desk
import uk.gov.homeoffice.drt.ports.Queues.Queue

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}

trait FlexedTerminalDeskLimitsLike extends TerminalDeskLimitsLike {
  val terminalDesksByMinute: List[Int]
  val flexedQueues: Set[Queue]
  val minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]
  val maxDesksByQueue24Hrs: Map[Queue, QueueCapacityProvider]

  def maxProcessors(minuteMillis: NumericRange[Long],
                    queue: Queue,
                    allocatedDesks: Map[Queue, List[Int]])
                   (implicit ec: ExecutionContext): Future[WorkloadProcessorsProvider] =
    if (flexedQueues.contains(queue)) {
      val deployedByQueue = allocatedDesks.values.toList
      val totalDeployed = if (deployedByQueue.nonEmpty) reduceIterables[Int](deployedByQueue)(_ + _) else List()
      val processedQueues = allocatedDesks.keys.toSet
      val remainingFlexedQueues = flexedQueues -- (processedQueues + queue)
      val remainingMinDesks = DeskRecs.desksByMinuteForQueues(minDesksByQueue24Hrs, minuteMillis, remainingFlexedQueues).values.toList
      val desksByMinute: Iterable[Int] = reduceIterables[Int](terminalDesksByMinute :: totalDeployed :: remainingMinDesks)(_ - _)
      Future.successful(WorkloadProcessorsProvider(desksByMinute.map(d => Seq.fill(d)(Desk))))
    } else {
      maxDesksByQueue24Hrs.getOrElse(queue, EmptyCapacityProvider).capacityForPeriod(minuteMillis)
    }
}

case class FlexedTerminalDeskLimits(terminalDesksByMinute: List[Int],
                                    flexedQueues: Set[Queue],
                                    minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]],
                                    maxDesksByQueue24Hrs: Map[Queue, QueueCapacityProvider])
                                   (implicit ec: ExecutionContext) extends FlexedTerminalDeskLimitsLike {
  override def maxDesksForMinutes(minuteMillis: NumericRange[Long],
                         queue: Queue,
                         allocatedDesks: Map[Queue, List[Int]]): Future[WorkloadProcessorsProvider] = {
    maxProcessors(minuteMillis, queue, allocatedDesks)
  }
}
