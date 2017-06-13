package drt.client.components

import drt.shared._
import diode.data.{Pot, Ready}
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import drt.client.logger
import drt.client.modules.{GriddleComponentWrapper, ViewTools}
import drt.client.services.JSDateConversions.SDate
import drt.shared.FlightsApi.FlightsWithSplits
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.{TagMod, TagOf}

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.Dictionary
import scala.scalajs.js.annotation.{JSExportAll, ScalaJSDefined}
import scala.util.{Failure, Success, Try}
import logger._
import org.scalajs.dom.html.Div

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

object FlightTableComponents {

  def airportCodeComponent(portMapper: Map[String, Pot[AirportInfo]])(port: String): VdomElement = {
    val tt = airportCodeTooltipText(portMapper) _
    <.span(^.title := tt(port), port)
  }

  def airportCodeComponentLensed(portInfoPot: Pot[AirportInfo])(port: String): VdomElement = {
    val tt: Option[Pot[String]] = Option(potAirportInfoToTooltip(portInfoPot))
    log.info(s"making airport info $port $tt")
    <.span(^.title := airportInfoDefault(tt), port)
  }

  def airportCodeTooltipText(portMapper: Map[String, Pot[AirportInfo]])(port: String): String = {
    val portInfoOptPot = portMapper.get(port)

    val res: Option[Pot[String]] = portInfoOptPot.map {
      potAirportInfoToTooltip
    }
    airportInfoDefault(res)
  }

  private def airportInfoDefault(res: Option[Pot[String]]): String = {
    log.info(s"airportInfoDefault got one! $res")
    res match {
      case Some(Ready(v)) => v
      case _ => "waiting for info..."
    }
  }

  private def potAirportInfoToTooltip(info: Pot[AirportInfo]): Pot[String] = {
    info.map(i => s"${i.airportName}, ${i.city}, ${i.country}")
  }

  def originComponent(originMapper: (String) => (String)): js.Function = (props: js.Dynamic) => {
    val mod: TagMod = ^.title := originMapper(props.data.toString())
    <.span(props.data.toString(), mod).render
  }

  def dateTimeComponent(): js.Function = (props: js.Dynamic) => {
    val dt = props.data.toString()
    if (dt != "") {
      localDateTimeWithPopup(dt)
    } else {
      <.div.render
    }
  }

  def localDateTimeWithPopup(dt: String) = {
    if (dt.isEmpty) <.span() else localTimePopup(dt)
  }

  private def localTimePopup(dt: String) = {
    val p = Try {
      log.info(s"parsing $dt")
      val sdate = SDate.parse(dt)
      log.info(s"parsing to $sdate")
      val hhmm = f"${sdate.getHours}%02d:${sdate.getMinutes}%02d"
      val titlePopup: TagMod = ^.title := sdate.toLocalDateTimeString()
      <.span(hhmm, titlePopup)
    }.recover {
      case f =>
        log.error(s"$f from $dt")
        <.div(f.toString())
    }.get
    p.render
  }

  def millisDelta(time1: String, time2: String) = {
    SDate.parse(time1).millisSinceEpoch - SDate.parse(time2).millisSinceEpoch
  }


  def asOffset(delta: Long, range: Double) = {
    val aggression = 1.0019
    val deltaTranslate = 1700
    val scaledDelta = 1.0 * delta / 1000
    val isLate = delta < 0
    if (isLate) {
      (range / (1 + Math.pow(aggression, (scaledDelta + deltaTranslate))))
    }
    else {
      -(range / (1 + Math.pow(aggression, -1.0 * (scaledDelta - deltaTranslate))))
    }
  }

  def dateStringAsLocalDisplay(dt: String) = dt match {
    case "" => ""
    case some => SDate.parse(dt).toLocalDateTimeString()
  }

  def timelineCompFunc(flight: Arrival): VdomElement = {
    Try {
      timelineFunc(150 - 24, flight.SchDT, flight.ActDT, flight.ActChoxDT)
    }.recover {
      case e =>
        log.error(s"couldn't render timeline of $flight with $e")
        val recovery: VdomElement = <.div("uhoh!")
        recovery
    }.get
  }

  def timelineFunc(schPct: Int, sch: String, act: String, actChox: String): VdomElement = {
    val (actDeltaTooltip: String, actPct: Double, actClass: String) = pctAndClass(sch, act, schPct)
    val (actChoxToolTip: String, actChoxPct: Double, actChoxClass: String) = pctAndClass(sch, actChox, schPct)


    val longToolTip =
      s"""Sch: ${dateStringAsLocalDisplay(sch)}
         |Act: ${dateStringAsLocalDisplay(act)} $actDeltaTooltip
         |ActChox: ${dateStringAsLocalDisplay(actChox)} $actChoxToolTip
        """.stripMargin

    val actChoxDot = if (!actChox.isEmpty)
      <.i(^.className :=
        "dot act-chox-dot " + actChoxClass,
        ^.title := s"ActChox: $actChox $actChoxToolTip",
        ^.left := s"${actChoxPct}px")
    else <.span()


    val actWidth = (actChoxPct + 24) - actPct

    val schDot = <.i(^.className := "dot sch-dot",
      ^.title := s"Scheduled\n$longToolTip", ^.left := s"${schPct}px")
    val
    actDot = if (!act.isEmpty) <.i(^.className := "dot act-dot "
      + actClass,
      ^.title
        := s"Actual: ${dateStringAsLocalDisplay(act)}",
      ^.width := s"${actWidth}px",
      ^.left := s"${actPct}px")
    else <.span()

    val dots = schDot :: actDot ::
      actChoxDot :: Nil

    <.div(schDot, actDot, actChoxDot, ^.className := "timeline-container", ^.title := longToolTip)

  }

  private def pctAndClass(sch: String, act: String, schPct: Int) = {
    val actDelta = millisDelta(sch, act)
    val actDeltaTooltip = {
      val dm = (actDelta / 60000)
      Math.abs(dm) + s"mins ${deltaMessage(actDelta)}"
    }
    val actPct = schPct + asOffset(actDelta, 150.0)
    val actClass: String = deltaMessage(actDelta)
    (actDeltaTooltip, actPct, actClass)
  }

  def deltaMessage(actDelta: Long) = {
    val actClass = actDelta match {
      case d if d < 0 => "late"
      case d if d == 0 => "on-time"
      case d if d > 0 => "early"
    }
    actClass
  }

  val uniqueArrivalsWithCodeShares = CodeShares.uniqueArrivalsWithCodeshares((f: ApiFlightWithSplits) => identity(f.apiFlight)) _
}
