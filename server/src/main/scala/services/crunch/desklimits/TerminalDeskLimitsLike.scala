package services.crunch.desklimits

import drt.shared.Queues.Queue
import services.crunch.deskrecs.DeskRecs

import scala.collection.immutable.{Map, NumericRange}

trait TerminalDeskLimitsLike {
  val minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]

  def minDesksForMinutes(minuteMillis: NumericRange[Long],
                         queue: Queue): Iterable[Int] =
    DeskRecs.desksForMillis(minuteMillis, minDesksByQueue24Hrs(queue)).toList

  def maxDesksForMinutes(minuteMillis: NumericRange[Long],
                         queue: Queue,
                         existingAllocations: Map[Queue, List[Int]]): Iterable[Int]
}
