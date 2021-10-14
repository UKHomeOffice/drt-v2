package services.crunch.desklimits.flexed

import uk.gov.homeoffice.drt.ports.Queues.Queue
import services.crunch.desklimits.{EmptyCapacityProvider, QueueCapacityProvider, TerminalDeskLimitsLike}
import services.crunch.deskrecs.DeskRecs
import services.graphstages.Crunch.reduceIterables

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.Future

trait FlexedTerminalDeskLimitsLike extends TerminalDeskLimitsLike {
  val terminalDesksByMinute: List[Int]
  val flexedQueues: Set[Queue]
  val minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]
  val maxDesksByQueue24Hrs: Map[Queue, QueueCapacityProvider]

  def maxDesks(minuteMillis: NumericRange[Long],
               queue: Queue,
               allocatedDesks: Map[Queue, List[Int]]): Future[Iterable[Int]] =
    if (flexedQueues.contains(queue)) {
      val deployedByQueue = allocatedDesks.values.toList
      val totalDeployed = if (deployedByQueue.nonEmpty) reduceIterables[Int](deployedByQueue)(_ + _) else List()
      val processedQueues = allocatedDesks.keys.toSet
      val remainingFlexedQueues = flexedQueues -- (processedQueues + queue)
      val remainingMinDesks = DeskRecs.desksByMinuteForQueues(minDesksByQueue24Hrs, minuteMillis, remainingFlexedQueues).values.toList
      Future.successful(reduceIterables[Int](terminalDesksByMinute :: totalDeployed :: remainingMinDesks)(_ - _))
    } else {
      maxDesksByQueue24Hrs.getOrElse(queue, EmptyCapacityProvider).capacityAt(minuteMillis)
    }
}

case class FlexedTerminalDeskLimits(terminalDesksByMinute: List[Int],
                                    flexedQueues: Set[Queue],
                                    minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]],
                                    maxDesksByQueue24Hrs: Map[Queue, QueueCapacityProvider]) extends FlexedTerminalDeskLimitsLike {
  def maxDesksForMinutes(minuteMillis: NumericRange[Long],
                         queue: Queue,
                         allocatedDesks: Map[Queue, List[Int]]): Future[Iterable[Int]] =
    maxDesks(minuteMillis, queue, allocatedDesks)
}
