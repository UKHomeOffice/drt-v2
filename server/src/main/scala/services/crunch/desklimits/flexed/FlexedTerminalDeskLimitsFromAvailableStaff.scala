package services.crunch.desklimits.flexed

import services.crunch.desklimits.QueueCapacityProvider
import uk.gov.homeoffice.drt.ports.Queues.Queue
import services.crunch.deskrecs.DeskRecs
import services.graphstages.Crunch

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}

case class FlexedTerminalDeskLimitsFromAvailableStaff(totalStaffByMinute: List[Int],
                                                      terminalDesksByMinute: List[Int],
                                                      flexedQueues: Set[Queue],
                                                      minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]],
                                                      maxDesksByQueue24Hrs: Map[Queue, QueueCapacityProvider])
                                                     (implicit ec: ExecutionContext) extends FlexedTerminalDeskLimitsLike {
  def maxDesksForMinutes(minuteMillis: NumericRange[Long], queue: Queue, allocatedDesks: Map[Queue, List[Int]]): Future[Iterable[Int]] = {
    val eventualAvailableDesksByMinute = maxDesks(minuteMillis, queue, allocatedDesks)
    val availableStaffByMinute = availableStaffForMinutes(minuteMillis, queue, allocatedDesks)

    eventualAvailableDesksByMinute.map { availableDesksByMinute =>
      Crunch.reduceIterables[Int](List(availableDesksByMinute, availableStaffByMinute))(Math.min)
    }
  }

  def availableStaffForMinutes(minuteMillis: NumericRange[Long],
                               queue: Queue,
                               allocatedDesks: Map[Queue, List[Int]]): Iterable[Int] = {
    val processedQueues = allocatedDesks.keys.toSet
    val deployedByQueue = allocatedDesks.values.toList
    val totalDeployedByMinute = if (deployedByQueue.nonEmpty) Crunch.reduceIterables[Int](deployedByQueue)(_ + _) else List()

    val remainingQueues = minDesksByQueue24Hrs.keys.toSet -- (processedQueues + queue)
    val minDesksForRemainingQueuesByMinute = DeskRecs.desksByMinuteForQueues(minDesksByQueue24Hrs, minuteMillis, remainingQueues).values.toList
    val minimumPromisedStaffByMinute = if (minDesksForRemainingQueuesByMinute.nonEmpty) Crunch.reduceIterables[Int](minDesksForRemainingQueuesByMinute)(_ + _) else List()

    Crunch
      .reduceIterables[Int](List(totalStaffByMinute, totalDeployedByMinute, minimumPromisedStaffByMinute))(_ - _)
      .map(Math.max(_, 0))
  }
}
