package drt.client.components

import diode.data.{Pot, Ready}
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagMod, TagOf}
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

  def originComponent(originMapper: String => String): js.Function = (props: js.Dynamic) => {
    val mod: TagMod = ^.title := originMapper(props.data.toString)
    <.span(props.data.toString(), mod).render
  }

  def localDateTimeWithPopup(dt: Option[MillisSinceEpoch]): TagMod = {
    dt.map(millis => localTimePopup(millis)).getOrElse(<.span())
  }

  def localTimePopup(dt: MillisSinceEpoch): VdomElement = {
    sdateLocalTimePopup(SDate(dt)).render
  }

  def millisToDisembark(pax: Int): Long = {
    val minutesToDisembark = (pax.toDouble / 20).ceil
    val oneMinuteInMillis = 60 * 1000
    (minutesToDisembark * oneMinuteInMillis).toLong
  }

  def pcpTimeRange(arrival: Arrival, bestPax: Arrival => Int): TagOf[Div] =
    arrival.PcpTime.map { pcpTime: MillisSinceEpoch =>
      val sdateFrom = SDate(MilliDate(pcpTime))
      val sdateTo = SDate(MilliDate(pcpTime + millisToDisembark(bestPax(arrival))))
      <.div(
        sdateLocalTimePopup(sdateFrom),
        " \u2192 ",
        sdateLocalTimePopup(sdateTo)
      )
    } getOrElse {
      <.div()
    }


  def sdateLocalTimePopup(sdate: SDateLike): TagOf[Span] = {
    val hhmm = f"${sdate.getHours()}%02d:${sdate.getMinutes()}%02d"
    val titlePopup: TagMod = ^.title := sdate.toLocalDateTimeString()
    <.span(hhmm, titlePopup)
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

  def dateStringAsLocalDisplay(dt: Option[MillisSinceEpoch]): String = dt match {
    case None => ""
    case Some(millis) => SDate(millis).toLocalDateTimeString()
  }

  def timelineCompFunc: Arrival => TagMod = (flight: Arrival) => {
    Try {
      timelineFunc(schPct = 150 - 24, sch = flight.Scheduled, act = flight.Actual, actChox = flight.ActualChox, estDt = flight.Estimated, estChoxDt = flight.EstimatedChox)
    }.recover {
      case e =>
        log.error(msg = s"couldn't render timeline of $flight with $e")
        <.div("uhoh!")
    }.get
  }

  def timelineFunc(schPct: Int, sch: MillisSinceEpoch, act: Option[MillisSinceEpoch], actChox: Option[MillisSinceEpoch], estDt: Option[MillisSinceEpoch], estChoxDt: Option[MillisSinceEpoch]): VdomElement = {
    val (actDeltaTooltip: String, actPct: Double, actClass: String) = pctAndClass(sch, act, schPct)
    val (_: String, estPct: Double, estClass: String) = pctAndClass(sch, estDt, schPct)
    val (_: String, estChoxPct: Double, _: String) = pctAndClass(sch, estChoxDt, schPct)
    val (actChoxToolTip: String, actChoxPct: Double, actChoxClass: String) = pctAndClass(sch, actChox, schPct)


    val longToolTip =
      s"""Sch: ${dateStringAsLocalDisplay(Some(sch))}
         |Act: ${dateStringAsLocalDisplay(act)} $actDeltaTooltip
         |ActChox: ${dateStringAsLocalDisplay(actChox)} $actChoxToolTip
         |Est: ${dateStringAsLocalDisplay(estDt)}
         |EstChox: ${dateStringAsLocalDisplay(estChoxDt)}
        """.stripMargin

    val actChoxDot = actChox.map { millis =>
      <.i(^.className :=
        "dot act-chox-dot " + actChoxClass,
        ^.title := s"ActChox: $millis $actChoxToolTip",
        ^.left := s"${actChoxPct}px")
    }.getOrElse {
      <.span()
    }

    val actWidth = (actChoxPct + 24) - actPct

    val schDot = <.i(^.className := "dot sch-dot",
      ^.title := s"Scheduled\n$longToolTip", ^.left := s"${schPct}px")

    val actDot = act.map { _ =>
      <.i(^.className := "dot act-dot "
        + actClass,
        ^.title
          := s"Actual: ${dateStringAsLocalDisplay(act)}",
        ^.width := s"${actWidth}px",
        ^.left := s"${actPct}px")
    } getOrElse <.span()


    val estDot = estDt.map { _ =>
      <.i(^.className := "dot est-dot "
        + estClass,
        ^.title
          := s"Est: ${dateStringAsLocalDisplay(estDt)}",
        ^.left := s"${estPct}px")
    } getOrElse <.span()

    val estChoxDot = estChoxDt.map { _ =>
      <.i(^.className := "dot est-chox-dot "
        + estClass,
        ^.title
          := s"Est: ${dateStringAsLocalDisplay(estChoxDt)}",
        ^.left := s"${estChoxPct}px")
    } getOrElse <.span()

    <.div(schDot, estDot, estChoxDot, actDot, actChoxDot, ^.className := "timeline-container", ^.title := longToolTip)

  }

  private def pctAndClass(sch: MillisSinceEpoch, act: Option[MillisSinceEpoch], schPct: Int): (String, Double, String) = {
    act.map { actMillis =>
      val delta = sch - actMillis
      val deltaTooltip = {
        val dm = delta / 60000
        Math.abs(dm) + s"mins ${deltaMessage(delta)}"
      }
      val actPct = schPct + asOffset(delta, 150.0)

      val deltaClass: String = deltaMessage(delta)
      (deltaTooltip, actPct, deltaClass)

    }.getOrElse(("", schPct.toDouble, ""))
  }

  def deltaMessage(actDelta: Long): String = {
    val actClass = actDelta match {
      case d if d < 0 => "late"
      case d if d == 0 => "on-time"
      case d if d > 0 => "early"
    }
    actClass
  }

  val uniqueArrivalsWithCodeShares: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])] = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => identity(f.apiFlight))
}
