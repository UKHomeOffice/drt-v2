package services.crunch.desklimits.flexed

import uk.gov.homeoffice.drt.ports.Queues.Queue
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.DeskRecs
import services.graphstages.Crunch.reduceIterables

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.Future

trait FlexedTerminalDeskLimitsLike extends TerminalDeskLimitsLike {
  val terminalDesksByMinute: List[Int]
  val flexedQueues: Set[Queue]
  val minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]
  val maxDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]

  def maxDesks(minuteMillis: NumericRange[Long],
               queue: Queue,
               allocatedDesks: Map[Queue, List[Int]]): Iterable[Int] =
    if (flexedQueues.contains(queue)) {
      val deployedByQueue = allocatedDesks.values.toList
      val totalDeployed = if (deployedByQueue.nonEmpty) reduceIterables[Int](deployedByQueue)(_ + _) else List()
      val processedQueues = allocatedDesks.keys.toSet
      val remainingFlexedQueues = flexedQueues -- (processedQueues + queue)
      val remainingMinDesks = DeskRecs.desksByMinuteForQueues(minDesksByQueue24Hrs, minuteMillis, remainingFlexedQueues).values.toList
      reduceIterables[Int](terminalDesksByMinute :: totalDeployed :: remainingMinDesks)(_ - _)
    } else DeskRecs.desksForMillis(minuteMillis, maxDesksByQueue24Hrs(queue)).toList
}

case class FlexedTerminalDeskLimits(terminalDesksByMinute: List[Int],
                                    flexedQueues: Set[Queue],
                                    minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]],
                                    maxDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]) extends FlexedTerminalDeskLimitsLike {
  def maxDesksForMinutes(minuteMillis: NumericRange[Long],
                         queue: Queue,
                         allocatedDesks: Map[Queue, List[Int]]): Future[Iterable[Int]] =
    Future.successful(maxDesks(minuteMillis, queue, allocatedDesks))

}
