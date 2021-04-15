package drt.shared.api

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared.api.WalkTime.millisToMinutes
import upickle.default.{macroRW, _}

import scala.collection.immutable.Map

case class WalkTime(gateOrStand: String, terminal: Terminal, walkTimeMillis: Long, unitDisplayAsSubject: Boolean) {
  val inMinutes: String = millisToMinutes(walkTimeMillis, unitDisplayAsSubject)
}

case class TerminalWalkTimes(gateWalktimes: Map[String, WalkTime], standWalkTimes: Map[String, WalkTime])

object TerminalWalkTimes {
  implicit val rw: ReadWriter[TerminalWalkTimes] = macroRW
}

case class WalkTimes(byTerminal: Map[Terminal, TerminalWalkTimes]) {

  def walkTimeForArrival(defaultWalkTime: Long)
                        (gate: Option[String], stand: Option[String], terminal: Terminal): String = {
    val defaultString = s"${millisToMinutes(defaultWalkTime, false)} (default walk time for terminal)"
    val maybeWalkTime: Option[String] = (gate, stand, byTerminal.get(terminal)) match {
      case (Some(g), _, Some(t)) if t.gateWalktimes.contains(g) =>
        byTerminal(terminal).gateWalktimes.get(g).map(_.inMinutes + " walk time")
      case (_, Some(s), Some(t)) if t.standWalkTimes.contains(s) =>
        byTerminal(terminal).standWalkTimes.get(s).map(_.inMinutes + " walk time")
      case _ => None
    }

    maybeWalkTime.getOrElse(defaultString)
  }

  def isEmpty = byTerminal.isEmpty
}

object WalkTimes {
  implicit val rw: ReadWriter[WalkTimes] = macroRW

  def apply(gateWalkTimes: Seq[WalkTime], standWalkTimes: Seq[WalkTime]): WalkTimes = {
    val gatesByTerminal = byTerminal(gateWalkTimes)
    val standsByTerminal = byTerminal(standWalkTimes)

    val keys = gatesByTerminal.keys ++ standsByTerminal.keys

    val twt: Map[Terminal, TerminalWalkTimes] = keys.map(key =>
      key -> TerminalWalkTimes(gatesByTerminal.getOrElse(key, Map()), standsByTerminal.getOrElse(key, Map()))
    ).toMap

    WalkTimes(twt)
  }

  def byTerminal(gateWalkTimes: Seq[WalkTime]): Map[Terminal, Map[String, WalkTime]] = gateWalkTimes
    .groupBy(_.terminal)
    .mapValues(
      _.groupBy(_.gateOrStand)
        .mapValues(_.head)
    )
}

object WalkTime {
  implicit val rw: ReadWriter[WalkTime] = macroRW

  def millisToMinutes(millis: MillisSinceEpoch, unitDisplayAsSubject: Boolean): String = {
    val inSeconds = millis / 1000
    val minutes = inSeconds / 60
    val minuteText = if (unitDisplayAsSubject || minutes == 1) "minute" else "minutes"
    s"$minutes $minuteText"
  }

}
