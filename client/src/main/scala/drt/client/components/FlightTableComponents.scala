package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.TagMod
import japgolly.scalajs.react.vdom.html_<^._
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.prediction.ToChoxModelAndFeatures
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis
import uk.gov.homeoffice.drt.time.SDateLike

object FlightTableComponents {

  def maybeLocalTimeWithPopup(dtList: Seq[ArrivalDisplayTime], maybeToolTip: Option[String] = None, isMobile: Boolean): TagMod = {
    localTimePopup(dtList.filter(_.time.nonEmpty)
      .map(a => if (isMobile) a.shortLabel -> a.time.get else a.label -> a.time.get).toMap, maybeToolTip, isMobile)
  }

  private def localTimePopup(dtMap: Map[String, MillisSinceEpoch], maybeToolTip: Option[String], isMobile: Boolean): VdomElement = {
    maybeToolTip match {
      case None => sdateLocalTimePopup(dtMap.map { case (k, dt) => k -> SDate(dt) }, isMobile)
      case Some(tooltip) => <.span(^.display := "flex", ^.flexWrap := "nowrap", sdateLocalTimePopup(dtMap.map { case (k, dt) => k -> SDate(dt) }, isMobile), <.span(^.marginLeft := "5px", Tippy.info(tooltip)))
    }
  }

  def millisToDisembark(pax: Int): Long = {
    val minutesToDisembark = (pax.toDouble / 20).ceil
    val oneMinuteInMillis = 60 * 1000
    (minutesToDisembark * oneMinuteInMillis).toLong
  }

  def actualMinutesToChox(arrival: Arrival): Option[MillisSinceEpoch] =
    arrival.ActualChox.flatMap(c => arrival.Actual.map(a => (c - a) / oneMinuteMillis))

  def estMinutesToChox(arrival: Arrival): Option[MillisSinceEpoch] =
    arrival.EstimatedChox.flatMap(c => arrival.Actual.map(a => (c - a) / oneMinuteMillis))

  def pcpTimeRange(fws: ApiFlightWithSplits, firstPaxOffMillis: MillisSinceEpoch): VdomElement =
    fws.apiFlight.PcpTime.map { pcpTime: MillisSinceEpoch =>
      val sdateFrom = SDate(MilliDate(pcpTime))
      val sdateTo = SDate(MilliDate(pcpTime + millisToDisembark(fws.pcpPaxEstimate.pax.getOrElse(0))))
      val postTouchdownTimes = <.span(
        <.h3("Minutes to chox from touchdown"),
        s"DRT predicted: ${fws.apiFlight.Predictions.predictions.get(ToChoxModelAndFeatures.targetName).map(c => s"${c.toString}m").getOrElse("-")}", <.br(),
        s"Feed estimated: ${estMinutesToChox(fws.apiFlight).map(c => s"${c.toString}m").getOrElse("-")}", <.br(),
        s"Feed actual: ${actualMinutesToChox(fws.apiFlight).map(c => s"${c.toString}m").getOrElse("-")}", <.br(),
        <.h3("Other times"),
        s"Chox to doors open: ${firstPaxOffMillis / oneMinuteMillis}m", <.br(),
        s"Walk time from gate to arrivals hall: ${fws.apiFlight.walkTime(firstPaxOffMillis, considerPredictions = true).map(ms => s"${(ms / oneMinuteMillis).toString}m").getOrElse("-")}", <.br(),
      )
      val content = <.div(^.display := "grid", ^.whiteSpace := "nowrap",
        sdateFrom.toHoursAndMinutes,
        " \u2192 ",
        sdateTo.toHoursAndMinutes,
      )
      Tippy.describe(postTouchdownTimes, content).vdomElement
    } getOrElse {
      <.div()
    }

  def sdateLocalTimePopup(sdateMap: Map[String, SDateLike], isMobile: Boolean): Unmounted[Tippy.Props, Unit, Unit] = {
    val sdateOpt = getExpectedTime(sdateMap, isMobile)
    val hhmm = sdateOpt.map(sdate => f"${sdate.getHours()}%02d:${sdate.getMinutes()}%02d").getOrElse("")
    Tippy.describe(<.table(
      <.tbody(<.tr(^.colSpan := 2, <.th(^.width := "8em", "Status"), <.th("Datetime")),
        sdateMap.map { case (timeName, sdate) => <.tr(<.td(timeName), <.td(sdate.toLocalDateTimeString()))
        }.toTagMod, ^.display := "inline")), hhmm)
  }

  def getExpectedTime(sdateMap: Map[String, SDateLike], isMobile: Boolean): Option[SDateLike] = {
    if (isMobile) {
      (sdateMap.contains("ActChox"), sdateMap.contains("EstChox"), sdateMap.contains("Tou"),
        sdateMap.contains("Est"), sdateMap.contains("Pre")) match {
        case (true, _, _, _, _) => sdateMap.get("ActChox")
        case (_, true, _, _, _) => sdateMap.get("EstChox")
        case (_, _, true, _, _) => sdateMap.get("Tou")
        case (_, _, _, true, _) => sdateMap.get("Est")
        case (_, _, _, _, true) => sdateMap.get("Pre")
        case _ => sdateMap.get("Sch")
      }
    } else {
      (sdateMap.contains("ActualChox"), sdateMap.contains("EstimatedChox"), sdateMap.contains("Touchdown"),
        sdateMap.contains("Estimated"), sdateMap.contains("Predicated")) match {
        case (true, _, _, _, _) => sdateMap.get("ActualChox")
        case (_, true, _, _, _) => sdateMap.get("EstimatedChox")
        case (_, _, true, _, _) => sdateMap.get("Touchdown")
        case (_, _, _, true, _) => sdateMap.get("Estimated")
        case (_, _, _, _, true) => sdateMap.get("Predicated")
        case _ => sdateMap.get("Scheduled")
      }
    }

  }

  val uniqueArrivalsWithCodeShares: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])] = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => identity(f.apiFlight))
}
