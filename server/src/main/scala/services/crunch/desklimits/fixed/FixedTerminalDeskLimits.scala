package services.crunch.desklimits.fixed

import services.WorkloadProcessorsProvider
import services.crunch.desklimits.{EmptyCapacityProvider, QueueCapacityProvider, TerminalDeskLimitsLike}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.time.LocalDate

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.Future

case class FixedTerminalDeskLimits(minDesksByQueue24Hrs: LocalDate => Map[Queue, IndexedSeq[Int]],
                                   maxDesksByQueue24Hrs: Map[Queue, QueueCapacityProvider],
                                  ) extends TerminalDeskLimitsLike {
  override def maxDesksForMinutes(minuteMillis: NumericRange[Long],
                                  queue: Queue,
                                  allocatedDesks: Map[Queue, List[Int]]): Future[WorkloadProcessorsProvider] = {
    maxDesksByQueue24Hrs.getOrElse(queue, EmptyCapacityProvider).capacityForPeriod(minuteMillis)
  }
}
