package services.crunch.desklimits.flexed

import drt.shared.Queues.Queue
import services.crunch.deskrecs.DeskRecs
import services.graphstages.Crunch.listOp

import scala.collection.immutable.{Map, NumericRange}

case class FlexedTerminalDeskLimitsFromAvailableStaff(totalStaffByMinute: List[Int],
                                                      terminalDesksByMinute: List[Int],
                                                      flexedQueues: Set[Queue],
                                                      minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]],
                                                      maxDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]) extends FlexedTerminalDeskLimitsLike {
  def maxDesksForMinutes(minuteMillis: NumericRange[Long], queue: Queue, allocatedDesks: Map[Queue, List[Int]]): Iterable[Int] = {
    val availableDesksByMinute = maxDesks(minuteMillis, queue, allocatedDesks)
    val availableStaffByMinute = availableStaffForMinutes(minuteMillis, queue, allocatedDesks)

    List(availableDesksByMinute, availableStaffByMinute).reduce(listOp[Int](Math.min))
  }

  def availableStaffForMinutes(minuteMillis: NumericRange[Long],
                               queue: Queue,
                               allocatedDesks: Map[Queue, List[Int]]): Iterable[Int] = {
    val processedQueues = allocatedDesks.keys.toSet
    val deployedByQueue = allocatedDesks.values.toList
    val totalDeployedByMinute = if (deployedByQueue.nonEmpty) deployedByQueue.reduce(listOp[Int](_ + _)) else List()

    val remainingQueues = minDesksByQueue24Hrs.keys.toSet -- (processedQueues + queue)
    val minDesksForRemainingQueuesByMinute = DeskRecs.desksByMinuteForQueues(minDesksByQueue24Hrs, minuteMillis, remainingQueues).values.toList
    val minimumPromisedStaffByMinute = if (minDesksForRemainingQueuesByMinute.nonEmpty) minDesksForRemainingQueuesByMinute.reduce(listOp[Int](_ + _)) else List()

    List(totalStaffByMinute, totalDeployedByMinute, minimumPromisedStaffByMinute).reduce(listOp[Int](_ - _))
  }
}
