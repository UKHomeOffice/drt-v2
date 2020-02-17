package services.crunch.deskrecs.fixed

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import services.TryCrunch
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.TerminalDeskRecsProviderLike

import scala.collection.immutable.{Map, NumericRange}

case class FixedTerminalDeskRecsProvider(slas: Map[Queue, Int],
                                         cruncher: TryCrunch,
                                         bankSize: Int) extends TerminalDeskRecsProviderLike {
  override def desksAndWaits(minuteMillis: NumericRange[MillisSinceEpoch],
                             loads: Map[Queue, Seq[Double]],
                             minMaxDeskProvider: TerminalDeskLimitsLike): Map[Queue, (List[Int], List[Int])] =
    staticDesksAndWaits(minuteMillis, loads, Map(), minMaxDeskProvider)
}
