package services.crunch.desklimits.flexed

import drt.shared.Queues.Queue
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.DeskRecs
import services.graphstages.Crunch.listOp

import scala.collection.immutable.{Map, NumericRange}

case class FlexedTerminalDeskLimitsFromAvailableStaff(totalStaffByMinute: List[Int],
                                                      terminalDesksByMinute: List[Int],
                                                      flexedQueues: Set[Queue],
                                                      minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]],
                                                      maxDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]) extends TerminalDeskLimitsLike {
  def maxDesksForMinutes(minuteMillis: NumericRange[Long], queue: Queue, allocatedDesks: Map[Queue, (List[Int], List[Int])]): Iterable[Int] = {
    val processedQueues = allocatedDesks.keys.toSet
    val deployedByQueue = allocatedDesks.values.map(_._1).toList
    val totalDeployedByMinute = if (deployedByQueue.nonEmpty) deployedByQueue.reduce(listOp[Int](_ + _)) else List()

    val availableDesksByMinute = if (flexedQueues.contains(queue)) {
      val remainingFlexedQueues = flexedQueues -- (processedQueues + queue)
      val minDesksForRemainingFlexedQueuesByMinute = DeskRecs.desksByMinuteForQueues(minDesksByQueue24Hrs, minuteMillis, remainingFlexedQueues).values.toList
      (terminalDesksByMinute :: totalDeployedByMinute :: minDesksForRemainingFlexedQueuesByMinute).reduce(listOp[Int](_ - _))
    } else DeskRecs.desksByMinute(minuteMillis, maxDesksByQueue24Hrs(queue))

    val remainingQueues = minDesksByQueue24Hrs.keys.toSet -- (processedQueues + queue)
    val minDesksForRemainingQueuesByMinute = DeskRecs.desksByMinuteForQueues(minDesksByQueue24Hrs, minuteMillis, remainingQueues).values.toList
    val minimumPromisedStaffByMinute = if (minDesksForRemainingQueuesByMinute.nonEmpty) minDesksForRemainingQueuesByMinute.reduce(listOp[Int](_ + _)) else List()
    val availableStaff = List(totalStaffByMinute, totalDeployedByMinute, minimumPromisedStaffByMinute).reduce(listOp[Int](_ - _))

    List(availableDesksByMinute, availableStaff).reduce(listOp[Int](Math.min))
  }
}
