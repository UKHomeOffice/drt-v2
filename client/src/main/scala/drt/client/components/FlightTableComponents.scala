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

  def maybeLocalTimeWithPopup(dt: Option[MillisSinceEpoch], maybeToolTip: Option[String] = None): TagMod = {
    dt.map(millis => localTimePopup(millis, maybeToolTip)).getOrElse(<.span())
  }

  def localTimePopup(dt: MillisSinceEpoch, maybeToolTip: Option[String]): VdomElement = {
    maybeToolTip match {
      case None => sdateLocalTimePopup(SDate(dt))
      case Some(tooltip) => <.span(^.display := "flex", ^.flexWrap := "nowrap", sdateLocalTimePopup(SDate(dt)), <.span(^.marginLeft := "5px", Tippy.info(tooltip)))
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

  def sdateLocalTimePopup(sdate: SDateLike): Unmounted[Tippy.Props, Unit, Unit] = {
    val hhmm = f"${sdate.getHours()}%02d:${sdate.getMinutes()}%02d"
    Tippy.describe(<.span(sdate.toLocalDateTimeString(), ^.display := "inline"), hhmm)
  }

  val uniqueArrivalsWithCodeShares: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])] = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => identity(f.apiFlight))
}
