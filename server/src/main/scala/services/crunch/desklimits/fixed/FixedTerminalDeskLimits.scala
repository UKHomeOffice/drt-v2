package services.crunch.desklimits.fixed

import services.crunch.desklimits.{EmptyCapacityProvider, QueueCapacityProvider, TerminalDeskLimitsLike}
import uk.gov.homeoffice.drt.ports.Queues.Queue

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.Future

case class FixedTerminalDeskLimits(minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]],
                                   maxDesksByQueue24Hrs: Map[Queue, QueueCapacityProvider],
                                  ) extends TerminalDeskLimitsLike {
  override def maxDesksForMinutes(minuteMillis: NumericRange[Long],
                                  queue: Queue,
                                  allocatedDesks: Map[Queue, List[Int]]): Future[IndexedSeq[Int]] = {
    maxDesksByQueue24Hrs.getOrElse(queue, EmptyCapacityProvider).capacityForPeriod(minuteMillis)
  }
}
