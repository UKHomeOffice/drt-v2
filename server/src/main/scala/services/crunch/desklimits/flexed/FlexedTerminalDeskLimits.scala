package services.crunch.desklimits.flexed

import drt.shared.Queues.Queue
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.DeskRecs
import services.graphstages.Crunch.listOp

import scala.collection.immutable.{Map, NumericRange}

case class FlexedTerminalDeskLimits(terminalDesksByMinute: List[Int],
                                    flexedQueues: Set[Queue],
                                    minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]],
                                    maxDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]) extends TerminalDeskLimitsLike {
  def maxDesksForMinutes(minuteMillis: NumericRange[Long],
                         queue: Queue,
                         allocatedDesks: Map[Queue, (List[Int], List[Int])]): Iterable[Int] =
    if (flexedQueues.contains(queue)) {
      val deployedByQueue = allocatedDesks.values.map(_._1).toList
      val totalDeployed = if (deployedByQueue.nonEmpty) deployedByQueue.reduce(listOp[Int](_ + _)) else List()
      val processedQueues = allocatedDesks.keys.toSet
      val remainingFlexedQueues = flexedQueues -- (processedQueues + queue)
      val remainingMinDesks = DeskRecs.desksByMinuteForQueues(minDesksByQueue24Hrs, minuteMillis, remainingFlexedQueues).values.toList

      (terminalDesksByMinute :: totalDeployed :: remainingMinDesks).reduce(listOp[Int](_ - _))
    } else DeskRecs.desksByMinute(minuteMillis, maxDesksByQueue24Hrs(queue)).toList
}
