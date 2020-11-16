package services.crunch.desklimits

import drt.shared.Queues.Queue
import services.crunch.deskrecs.DeskRecs

import scala.collection.immutable.{Map, NumericRange}

trait TerminalDeskLimitsLike {
  val minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]

  def deskLimitsForMinutes(minuteMillis: NumericRange[Long], queue: Queue, allocatedDesks: Map[Queue, List[Int]]): (Iterable[Int], Iterable[Int]) = {
    val maxDesks = maxDesksForMinutes(minuteMillis, queue, allocatedDesks)
    val minDesks = DeskRecs
      .desksForMillis(minuteMillis, minDesksByQueue24Hrs(queue))
      .toList.zip(maxDesks)
      .map { case (min, max) =>
        Math.min(min, max)
      }
    (minDesks, maxDesks)
  }

  def maxDesksForMinutes(minuteMillis: NumericRange[Long],
                         queue: Queue,
                         existingAllocations: Map[Queue, List[Int]]): Iterable[Int]
}
