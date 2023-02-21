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

  def maybeLocalTimeWithPopup(dtList: Seq[ArrivalDisplayTime], maybeToolTip: Option[String] = None): TagMod = {
    localTimePopup(dtList.filter(_.time.nonEmpty), maybeToolTip)
  }

  private def localTimePopup(dtList: Seq[ArrivalDisplayTime], maybeToolTip: Option[String]): VdomElement = {
    maybeToolTip match {
      case None => sdateLocalTimePopup(dtList)
      case Some(tooltip) => <.span(^.display := "flex", ^.flexWrap := "nowrap", sdateLocalTimePopup(dtList), <.span(^.marginLeft := "5px", Tippy.info(tooltip)))
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

  val justTimeDateFormat: Option[SDateLike] => String =
    sdateOpt => sdateOpt.map(sdate => f"${sdate.getHours()}%02d:${sdate.getMinutes()}%02d").getOrElse("")

  def sdateLocalTimePopup(dtList: Seq[ArrivalDisplayTime]): Unmounted[Tippy.Props, Unit, Unit] = {
    val sdateOpt = getExpectedTime(dtList)
    val hhmm = justTimeDateFormat(sdateOpt)
    Tippy.describe(<.table(
      <.tbody(<.tr(^.colSpan := 2, <.th(^.width := "8em", "Status"), <.th("Datetime")),
        dtList.map { adt => <.tr(<.td(adt.timeLabel.getLabel), <.td(justTimeDateFormat(adt.time.map(SDate(_)))))
        }.toTagMod, ^.display := "inline")), hhmm)
  }


  def getExpectedTime(dtList: Seq[ArrivalDisplayTime]): Option[SDateLike] = {
    (dtList.find(a => a.timeLabel.isInstanceOf[ActualChoxDisplayLabel]),
      dtList.find(a => a.timeLabel.isInstanceOf[EstimatedChoxDisplayLabel]),
      dtList.find(a => a.timeLabel.isInstanceOf[TouchdownDisplayLabel]),
      dtList.find(a => a.timeLabel.isInstanceOf[EstimatedDisplayLabel]),
      dtList.find(a => a.timeLabel.isInstanceOf[PredicatedDisplayLabel])) match {
      case (Some(actChox), _, _, _, _) => actChox.time.map(SDate(_))
      case (_, Some(estChox), _, _, _) => estChox.time.map(SDate(_))
      case (_, _, Some(tou), _, _) => tou.time.map(SDate(_))
      case (_, _, _, Some(est), _) => est.time.map(SDate(_))
      case (_, _, _, _, Some(pre)) => pre.time.map(SDate(_))
      case _ => dtList.find(a => a.timeLabel.isInstanceOf[ScheduleDisplayLabel]).flatMap(_.time.map(SDate(_)))
    }
  }

  val uniqueArrivalsWithCodeShares: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])] = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => identity(f.apiFlight))
}
