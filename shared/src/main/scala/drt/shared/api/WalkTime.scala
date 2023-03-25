package drt.shared.api

import drt.shared.TimeUtil._
import drt.shared.{MinuteAsAdjective, MinuteAsNoun}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import upickle.default.{macroRW, _}

import scala.util.Try
import scala.util.matching.Regex

case class WalkTime(gateOrStand: String, terminal: Terminal, walkTimeMillis: Long) {
  val inMinutes: Int = millisToMinutes(walkTimeMillis)
}

case class TerminalWalkTimes(gateWalktimes: Map[String, WalkTime], standWalkTimes: Map[String, WalkTime])

object TerminalWalkTimes {
  implicit val rw: ReadWriter[TerminalWalkTimes] = macroRW
}

case class WalkTimes(byTerminal: Map[Terminal, TerminalWalkTimes]) {

  def walkTimeStringForArrival(defaultWalkTime: Long)
                              (gate: Option[String], stand: Option[String], terminal: Terminal): String = {
    val defaultString = s"${MinuteAsNoun(millisToMinutes(defaultWalkTime)).display} (default walk time for terminal)"
    maybeWalkTimeMinutes(gate, stand, terminal).map(wtMins => MinuteAsAdjective(wtMins).display + " walk time").getOrElse(defaultString)
  }

  def maybeWalkTimeMinutes(gate: Option[String], stand: Option[String], terminal: Terminal): Option[Int] = {
    val maybeWalkTime = (gate, stand, byTerminal.get(terminal)) match {
      case (Some(g), _, Some(t)) if t.gateWalktimes.contains(g) =>
        byTerminal(terminal).gateWalktimes.get(g)
      case (_, Some(s), Some(t)) if t.standWalkTimes.contains(s) =>
        byTerminal(terminal).standWalkTimes.get(s)
      case _ => None
    }
    maybeWalkTime.map(_.inMinutes)
  }

  def walkTimeMillisForArrival(defaultWalkTime: Long)
                              (gate: Option[String], stand: Option[String], terminal: Terminal): Long = {
    val maybeWalkTime = (gate, stand, byTerminal.get(terminal)) match {
      case (Some(g), _, Some(t)) if t.gateWalktimes.contains(g) =>
        byTerminal(terminal).gateWalktimes.get(g)
      case (_, Some(s), Some(t)) if t.standWalkTimes.contains(s) =>
        byTerminal(terminal).standWalkTimes.get(s)
      case _ => None
    }

    maybeWalkTime.map(_.walkTimeMillis).getOrElse(defaultWalkTime)
  }

  def isEmpty: Boolean = byTerminal.isEmpty
}

object WalkTimes {
  implicit val rw: ReadWriter[WalkTimes] = macroRW

  def apply(gateWalkTimes: Iterable[WalkTime], standWalkTimes: Iterable[WalkTime]): WalkTimes = {
    val gatesByTerminal = byTerminal(gateWalkTimes)
    val standsByTerminal = byTerminal(standWalkTimes)

    val keys = gatesByTerminal.keys ++ standsByTerminal.keys

    val twt: Map[Terminal, TerminalWalkTimes] = keys.map(key =>
      key -> TerminalWalkTimes(gatesByTerminal.getOrElse(key, Map()), standsByTerminal.getOrElse(key, Map()))
    ).toMap

    WalkTimes(twt)
  }

  def byTerminal(gateWalkTimes: Iterable[WalkTime]): Map[Terminal, Map[String, WalkTime]] = gateWalkTimes
    .groupBy(_.terminal)
    .view
    .mapValues {
      _.groupBy(_.gateOrStand).map {
        case (gateOrStand, walkTimes) => gateOrStand -> walkTimes.head
      }
    }.toMap

  private def gateStandPatternMatchPair(gateStand: String): (Int, String) = {
    val pattern: Regex = "^[0-9]*[A-Z]$".r
    pattern.findFirstMatchIn(gateStand) match {
      case Some(_) => gateStand.substring(0, gateStand.length - 1).toInt -> gateStand.substring(gateStand.length - 1, gateStand.length)
      case None => Try(gateStand.toInt -> "").getOrElse(999 -> gateStand)
    }
  }

  def sortGateStandMap(unsortedMap: Map[String, WalkTime]): Seq[(String, WalkTime)] = {
    unsortedMap.map {
      case (k, v) => gateStandPatternMatchPair(k) -> v
    }.toList.groupBy(_._1._1).map {
      case (k, v) => k -> v.sortWith(_._1._2 < _._1._2)
    }.toList.sortWith(_._1 < _._1)
      .flatMap(_._2)
      .map { a => if (a._1._1 == 999) a._1._2 -> a._2 else (a._1._1 + a._1._2).trim -> a._2 }
  }

}

object WalkTime {
  implicit val rw: ReadWriter[WalkTime] = macroRW
}
