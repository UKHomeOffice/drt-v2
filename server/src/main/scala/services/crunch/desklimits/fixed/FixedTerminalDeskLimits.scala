package services.crunch.desklimits.fixed

import drt.shared.Queues.Queue
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.DeskRecs

import scala.collection.immutable.{Map, NumericRange}

case class FixedTerminalDeskLimits(minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]],
                                   maxDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]]) extends TerminalDeskLimitsLike {
  def maxDesksForMinutes(minuteMillis: NumericRange[Long],
                         queue: Queue,
                         allocatedDesks: Map[Queue, List[Int]]): List[Int] =
    DeskRecs.desksForMillis(minuteMillis, maxDesksByQueue24Hrs.getOrElse(queue, IndexedSeq.fill(24)(0))).toList
}
