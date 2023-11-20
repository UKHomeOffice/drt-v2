package services.crunch.desklimits.flexed

import org.slf4j.{Logger, LoggerFactory}
import services.WorkloadProcessorsProvider
import services.crunch.desklimits.{EmptyCapacityProvider, QueueCapacityProvider, TerminalDeskLimitsLike}
import services.crunch.deskrecs.DeskRecs
import services.graphstages.Crunch.reduceIterables
import uk.gov.homeoffice.drt.egates.Desk
import uk.gov.homeoffice.drt.ports.Queues.Queue

import scala.collection.immutable
import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.Future

trait FlexedTerminalDeskLimitsLike extends TerminalDeskLimitsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val terminalDesks: Int
  val flexedQueues: Set[Queue]
  val minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]
  val capacityByQueue: Map[Queue, QueueCapacityProvider]

  def maxProcessors(minuteMillis: NumericRange[Long],
                    queue: Queue,
                    allocatedDesks: Map[Queue, List[Int]]): Future[WorkloadProcessorsProvider] =
    if (flexedQueues.contains(queue)) {
      val deployedByQueue = allocatedDesks.values.toList
      val totalDeployed = if (deployedByQueue.nonEmpty) reduceIterables[Int](deployedByQueue)(_ + _) else List()
      val processedQueues = allocatedDesks.keys.toSet
      val remainingFlexedQueues = flexedQueues -- (processedQueues + queue)
      val remainingMinDesks = DeskRecs.desksByMinuteForQueues(minDesksByQueue24Hrs, minuteMillis, remainingFlexedQueues).values.toList
      val terminalDesksForPeriod: immutable.Seq[Int] = List.fill(minuteMillis.length)(terminalDesks)
      val desksByMinute: Iterable[Int] = reduceIterables[Int](terminalDesksForPeriod :: totalDeployed :: remainingMinDesks)(_ - _)
      Future.successful(WorkloadProcessorsProvider(desksByMinute.map(d => Seq.fill(d)(Desk))))
    } else {
      capacityByQueue.getOrElse(queue, {
        log.error(s"Didn't find a cap provider for $queue. Using an empty one...")
        EmptyCapacityProvider
      }).capacityForPeriod(minuteMillis)
    }
}

case class FlexedTerminalDeskLimits(terminalDesks: Int,
                                    flexedQueues: Set[Queue],
                                    minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]],
                                    capacityByQueue: Map[Queue, QueueCapacityProvider]
                                   ) extends FlexedTerminalDeskLimitsLike {
  override def maxDesksForMinutes(minuteMillis: NumericRange[Long],
                                  queue: Queue,
                                  allocatedDesks: Map[Queue, List[Int]]): Future[WorkloadProcessorsProvider] = {
    maxProcessors(minuteMillis, queue, allocatedDesks)
  }
}
