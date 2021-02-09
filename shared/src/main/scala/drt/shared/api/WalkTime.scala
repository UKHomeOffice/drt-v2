package drt.shared.api

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared.api.WalkTime.millisToMinutesAndSecondsString
import upickle.default.{macroRW, _}

import scala.collection.immutable.Map

case class WalkTime(gateOrStand: String, terminal: Terminal, walkTimeMillis: Long) {
  val inMinutesAndSeconds: String = millisToMinutesAndSecondsString(walkTimeMillis)
}

case class TerminalWalkTimes(gateWalktimes: Map[String, WalkTime], standWalkTimes: Map[String, WalkTime])

object TerminalWalkTimes {
  implicit val rw: ReadWriter[TerminalWalkTimes] = macroRW
}

case class WalkTimes(byTerminal: Map[Terminal, TerminalWalkTimes]) {

  def walkTimeForArrival(defaultWalkTime: Long)
                        (gate: Option[String], stand: Option[String], terminal: Terminal): String = {
    val defaultString = s"${millisToMinutesAndSecondsString(defaultWalkTime)} (default walk time for terminal)"
    val maybeWalkTime: Option[String] = (gate, stand, byTerminal.get(terminal)) match {
      case (Some(g), _, Some(t)) if t.gateWalktimes.contains(g) =>
        byTerminal(terminal).gateWalktimes.get(g).map(_.inMinutesAndSeconds)
      case (_, Some(s), Some(t)) if t.standWalkTimes.contains(s) =>
        byTerminal(terminal).standWalkTimes.get(s).map(_.inMinutesAndSeconds)
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

  def millisToMinutesAndSecondsString(millis: MillisSinceEpoch): String = {
    val inSeconds = millis / 1000
    val minutes = inSeconds / 60
    val seconds = inSeconds % 60

    val secondsString: Option[String] = if (seconds > 0) Option(s"$seconds seconds") else None
    val minutesString: Option[String] = if (minutes > 0) Option(s"$minutes minute${if (minutes > 1) "s" else ""}") else None

    (minutesString :: secondsString :: Nil).flatten.mkString(", ")
  }

}
