package drt.client.components

import diode.data.{Pot, Ready}
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import japgolly.scalajs.react.vdom.{TagMod, TagOf}
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html.{Div, Span}

import scala.scalajs.js
import scala.util.Try

object FlightTableComponents {

  def airportCodeComponent(portMapper: Map[String, Pot[AirportInfo]])(port: String): VdomElement = {
    val tt = airportCodeTooltipText(portMapper) _
    <.span(^.title := tt(port), port)
  }

  def airportCodeComponentLensed(portInfoPot: Pot[AirportInfo])(port: String): VdomElement = {
    val tt: Option[Pot[String]] = Option(potAirportInfoToTooltip(portInfoPot))
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
    res match {
      case Some(Ready(v)) => v
      case _ => "waiting for info..."
    }
  }

  private def potAirportInfoToTooltip(info: Pot[AirportInfo]): Pot[String] = {
    info.map(i => s"${i.airportName}, ${i.city}, ${i.country}")
  }

  def originComponent(originMapper: (String) => (String)): js.Function = (props: js.Dynamic) => {
    val mod: TagMod = ^.title := originMapper(props.data.toString)
    <.span(props.data.toString(), mod).render
  }

  def dateTimeComponent(): js.Function = (props: js.Dynamic) => {
    val dt = props.data.toString
    if (dt != "" && dt != "0") {
      Try {
        val sdate = SDate.parse(dt)
        sdateLocalTimePopup(sdate)
      }.recover {
        case f =>
        localDateTimeWithPopup(dt.toLong)
      }
    } else {
      <.div.render
    }
  }

  def localDateTimeWithPopup(dt: MillisSinceEpoch): TagMod = {
    if (dt == 0) <.span() else localTimePopup(dt)
  }

  def localTimePopup(dt: MillisSinceEpoch): VdomElement = {
    val p = Try {
      val sdate = SDate(dt)
      sdateLocalTimePopup(sdate)
    }.recover {
      case f =>
        log.error(s"$f from $dt")
        <.div("n/a")
    }.get
    p.render
  }

  def millisToDisembark(pax: Int): Long = {
    val minutesToDisembark = (pax.toDouble / 20).ceil
    val oneMinuteInMillis = 60 * 1000
    (minutesToDisembark * oneMinuteInMillis).toLong
  }

  def pcpTimeRange(arrival: Arrival, bestPax: (Arrival) => Int): TagOf[Div] = {
    val sdateFrom = SDate(MilliDate(arrival.PcpTime))
    val sdateTo = SDate(MilliDate(arrival.PcpTime + millisToDisembark(bestPax(arrival))))
    <.div(
      sdateLocalTimePopup(sdateFrom),
      " \u2192 ",
      sdateLocalTimePopup(sdateTo)
    )
  }

  def sdateLocalTimePopup(sdate: SDateLike): TagOf[Span] = {
    val hhmm = f"${sdate.getHours()}%02d:${sdate.getMinutes()}%02d"
    val titlePopup: TagMod = ^.title := sdate.toLocalDateTimeString()
    <.span(hhmm, titlePopup)
  }

  def millisDelta(time1: MillisSinceEpoch, time2: MillisSinceEpoch): Long = {
    time1 - time2
  }


  def asOffset(delta: Long, range: Double): Double = {
    if (delta == 0) 0d else {
      val aggression = 1.0019
      val deltaTranslate = 1700
      val scaledDelta = 1.0 * delta / 1000
      val isLate = delta < 0
      if (isLate) {
        range / (1 + Math.pow(aggression, scaledDelta + deltaTranslate))
      }
      else {
        -(range / (1 + Math.pow(aggression, -1.0 * (scaledDelta - deltaTranslate))))
      }
    }
  }

  def dateStringAsLocalDisplay(dt: MillisSinceEpoch): String = dt match {
    case 0 => ""
    case some => Try(SDate(dt).toLocalDateTimeString()).getOrElse("")
  }

  def timelineCompFunc(flight: Arrival): VdomElement = {
    Try {
      timelineFunc(150 - 24, flight.Scheduled, flight.Actual, flight.ActualChox, flight.Estimated, flight.EstimatedChox)
    }.recover {
      case e =>
        log.error(s"couldn't render timeline of $flight with $e")
        val recovery: VdomElement = <.div("uhoh!")
        recovery
    }.get
  }

  def timelineFunc(schPct: Int, sch: MillisSinceEpoch, act: MillisSinceEpoch, actChox: MillisSinceEpoch, estDt: MillisSinceEpoch, estChoxDt: MillisSinceEpoch): VdomElement = {
    val (actDeltaTooltip: String, actPct: Double, actClass: String) = pctAndClass(sch, act, schPct)
    val (estDeltaTooltip: String, estPct: Double, estClass: String) = pctAndClass(sch, estDt, schPct)
    val (estChoxDeltaTooltip: String, estChoxPct: Double, estChoxClass: String) = pctAndClass(sch, estChoxDt, schPct)
    val (actChoxToolTip: String, actChoxPct: Double, actChoxClass: String) = pctAndClass(sch, actChox, schPct)


    val longToolTip =
      s"""Sch: ${dateStringAsLocalDisplay(sch)}
         |Act: ${dateStringAsLocalDisplay(act)} $actDeltaTooltip
         |ActChox: ${dateStringAsLocalDisplay(actChox)} $actChoxToolTip
         |Est: ${dateStringAsLocalDisplay(estDt)}
         |EstChox: ${dateStringAsLocalDisplay(estChoxDt)}
        """.stripMargin

    val actChoxDot = if (actChox != 0)
      <.i(^.className :=
        "dot act-chox-dot " + actChoxClass,
        ^.title := s"ActChox: $actChox $actChoxToolTip",
        ^.left := s"${actChoxPct}px")
    else <.span()


    val actWidth = (actChoxPct + 24) - actPct

    val schDot = <.i(^.className := "dot sch-dot",
      ^.title := s"Scheduled\n$longToolTip", ^.left := s"${schPct}px")

    val actDot = if (act != 0) <.i(^.className := "dot act-dot "
      + actClass,
      ^.title
        := s"Actual: ${dateStringAsLocalDisplay(act)}",
      ^.width := s"${actWidth}px",
      ^.left := s"${actPct}px")
    else <.span()

    val estDot = if (estDt != 0) <.i(^.className := "dot est-dot "
      + estClass,
      ^.title
        := s"Est: ${dateStringAsLocalDisplay(estDt)}",
      ^.left := s"${estPct}px")
    else <.span()

    val estChoxDot = if (estChoxDt != 0) <.i(^.className := "dot est-chox-dot "
      + estClass,
      ^.title
        := s"Est: ${dateStringAsLocalDisplay(estChoxDt)}",
      ^.left := s"${estChoxPct}px")
    else <.span()

    <.div(schDot, estDot, estChoxDot, actDot, actChoxDot, ^.className := "timeline-container", ^.title := longToolTip)

  }

  private def pctAndClass(sch: MillisSinceEpoch, act: MillisSinceEpoch, schPct: Int) = {
    if (act == 0) {
      ("", schPct.toDouble, "")
    } else {
      val delta = millisDelta(sch, act)
      val deltaTooltip = {
        val dm = delta / 60000
        Math.abs(dm) + s"mins ${deltaMessage(delta)}"
      }
      val actPct = schPct + asOffset(delta, 150.0)

      val deltaClass: String = deltaMessage(delta)
      (deltaTooltip, actPct, deltaClass)
    }
  }

  def deltaMessage(actDelta: Long): String = {
    val actClass = actDelta match {
      case d if d < 0 => "late"
      case d if d == 0 => "on-time"
      case d if d > 0 => "early"
    }
    actClass
  }

  val uniqueArrivalsWithCodeShares: (Seq[ApiFlightWithSplits]) => List[(ApiFlightWithSplits, Set[Arrival])] = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => identity(f.apiFlight))
}
