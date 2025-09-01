package services.crunch.desklimits.flexed

import services.WorkloadProcessorsProvider
import services.crunch.desklimits.QueueCapacityProvider
import services.crunch.deskrecs.DeskRecs
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}

case class FlexedTerminalDeskLimitsFromAvailableStaff(totalStaffByMinute: List[Int],
                                                      terminalDesks: Int,
                                                      flexedQueues: Set[Queue],
                                                      minDesksByQueue24Hrs: LocalDate => Map[Queue, IndexedSeq[Int]],
                                                      capacityByQueue: Map[Queue, QueueCapacityProvider],
                                                      paxForQueue: (NumericRange[Long], Queue) => Future[Seq[Int]],
                                                     )
                                                     (implicit ec: ExecutionContext) extends FlexedTerminalDeskLimitsLike {
  override def maxDesksForMinutes(minuteMillis: NumericRange[Long], queue: Queue, allocatedDesks: Map[Queue, List[Int]]): Future[WorkloadProcessorsProvider] = {
    val eventualMaxProcessorsProvider = maxProcessors(minuteMillis, queue, allocatedDesks)
    val availableStaffByMinute = availableStaffForMinutes(minuteMillis, queue, allocatedDesks)

    eventualMaxProcessorsProvider.map { desksProvider =>
      val desksByMinute: IndexedSeq[Int] = desksProvider.processorsByMinute.map(_.processors.size)
      val availableDesks: Iterable[Int] = Crunch.reduceIterables[Int](List(desksByMinute, availableStaffByMinute))(Math.min)
      WorkloadProcessorsProvider(desksProvider.processorsByMinute.zip(availableDesks).map {
        case (processors, available) => processors.copy(processors = processors.processors.take(available))
      })
    }
  }

  private def availableStaffForMinutes(minuteMillis: NumericRange[Long],
                                       queue: Queue,
                                       allocatedDesks: Map[Queue, List[Int]]): Iterable[Int] = {
    val processedQueues = allocatedDesks.keys.toSet
    val deployedByQueue = allocatedDesks.values.toList
    val totalDeployedByMinute = if (deployedByQueue.nonEmpty) Crunch.reduceIterables[Int](deployedByQueue)(_ + _)
    else List()

    val date = SDate(minuteMillis.min).toLocalDate

    val remainingQueues = minDesksByQueue24Hrs(date).keys.toSet -- (processedQueues + queue)
    val minDesksForRemainingQueuesByMinute = DeskRecs.desksByMinuteForQueues(minDesksByQueue24Hrs(date), minuteMillis, remainingQueues).values.toList
    val minimumPromisedStaffByMinute = if (minDesksForRemainingQueuesByMinute.nonEmpty) Crunch.reduceIterables[Int](minDesksForRemainingQueuesByMinute)(_ + _)
    else List()

    Crunch
      .reduceIterables[Int](List(totalStaffByMinute, totalDeployedByMinute, minimumPromisedStaffByMinute))(_ - _)
      .map(Math.max(_, 0))
  }
}
