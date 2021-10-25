package services.crunch.desklimits.flexed

import services.WorkloadProcessorsProvider
import services.crunch.desklimits.QueueCapacityProvider
import uk.gov.homeoffice.drt.ports.Queues.Queue
import services.crunch.deskrecs.DeskRecs
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.egates.Desk

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}

case class FlexedTerminalDeskLimitsFromAvailableStaff(totalStaffByMinute: List[Int],
                                                      terminalDesksByMinute: List[Int],
                                                      flexedQueues: Set[Queue],
                                                      minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]],
                                                      maxDesksByQueue24Hrs: Map[Queue, QueueCapacityProvider])
                                                     (implicit ec: ExecutionContext) extends FlexedTerminalDeskLimitsLike {
  def maxDesksForMinutes(minuteMillis: NumericRange[Long], queue: Queue, allocatedDesks: Map[Queue, List[Int]]): Future[WorkloadProcessorsProvider] = {
    val eventualMaxProcessorsProvider = maxProcessors(minuteMillis, queue, allocatedDesks)
    val availableStaffByMinute = availableStaffForMinutes(minuteMillis, queue, allocatedDesks)

    eventualMaxProcessorsProvider.map { desksProvider =>
      val minute: IndexedSeq[Int] = desksProvider.processorsByMinute.map(_.processors.size)
      val availableDesks = Crunch.reduceIterables[Int](List(minute, availableStaffByMinute))(Math.min)
      WorkloadProcessorsProvider(availableDesks.map(d => Seq.fill(d)(Desk)))
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
