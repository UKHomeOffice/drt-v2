package services.crunch.desklimits

import services.crunch.deskrecs.DeskRecs
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.Queues.Queue

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}

trait QueueCapacityProvider {
  def capacityForPeriod(timeRange: NumericRange[Long]): Future[IndexedSeq[Int]]
}

object EmptyCapacityProvider extends QueueCapacityProvider {
  override def capacityForPeriod(timeRange: NumericRange[Long]): Future[IndexedSeq[Int]] =
    Future.successful(timeRange.map(_ => 0))
}

case class DeskCapacityProvider(maxPerHour: IndexedSeq[Int])
                               (implicit ec: ExecutionContext) extends QueueCapacityProvider {
  assert(maxPerHour.length == 24, s"There must be 24 hours worth of max desks defined. ${maxPerHour.length} found")

  override def capacityForPeriod(timeRange: NumericRange[Long]): Future[IndexedSeq[Int]] = {
    Future.successful(DeskRecs.desksForMillis(timeRange, maxPerHour))
  }
}

case class EgatesCapacityProvider(egatesProvider: () => Future[EgateBanksUpdates],
                                  defaultEgates: IndexedSeq[EgateBank])
                                 (implicit ec: ExecutionContext) extends QueueCapacityProvider {
  override def capacityForPeriod(timeRange: NumericRange[Long]): Future[IndexedSeq[Int]] = {
    egatesProvider().map { egates =>
      timeRange.map { time =>
        egates
          .applyForDate(time, defaultEgates)
          .map(_.gates.count(_ == true))
          .sum
      }
    }
  }
}

trait TerminalDeskLimitsLike {
  val minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]

  def deskLimitsForMinutes(minuteMillis: NumericRange[Long], queue: Queue, allocatedDesks: Map[Queue, List[Int]])
                          (implicit ec: ExecutionContext): Future[(Iterable[Int], Iterable[Int])] = {
    maxDesksForMinutes(minuteMillis, queue, allocatedDesks).map { maxDesks =>
      val minDesks = DeskRecs
        .desksForMillis(minuteMillis, minDesksByQueue24Hrs(queue))
        .toList.zip(maxDesks)
        .map { case (min, max) =>
          Math.min(min, max)
        }
      (minDesks, maxDesks)
    }
  }

  def maxDesksForMinutes(minuteMillis: NumericRange[Long],
                         queue: Queue,
                         existingAllocations: Map[Queue, List[Int]]): Future[Iterable[Int]]
}
