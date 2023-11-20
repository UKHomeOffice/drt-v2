package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import drt.shared.api.WalkTimes
import japgolly.scalajs.react.vdom.TagMod
import japgolly.scalajs.react.vdom.html_<^._
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.prediction.arrival.{ToChoxModelAndFeatures, WalkTimeModelAndFeatures}
import uk.gov.homeoffice.drt.time.MilliDate
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis

object FlightTableComponents {

  def maybeLocalTimeWithPopup(dt: Option[MillisSinceEpoch], maybeToolTip: Option[TagMod] = None, maybeInfo: Option[String] = None): TagMod = {
    dt match {
      case Some(millis) =>
        val sdate = SDate(millis)
        val hhmm = sdate.toHoursAndMinutes
        val toolTip = maybeToolTip.getOrElse(<.div(sdate.toLocalDateTimeString))
        val timeElement = Tippy.describe(<.span(toolTip, ^.display := "inline"), hhmm)
        maybeInfo match {
          case None => timeElement
          case Some(info) => <.span(^.display := "flex", ^.flexWrap := "nowrap", timeElement, <.span(^.marginLeft := "5px", Tippy.info(info)))
        }
      case None => <.span()
    }
  }

  private def millisToDisembark(pax: Int): Long = {
    val minutesToDisembark = (pax.toDouble / 20).ceil
    val oneMinuteInMillis = 60 * 1000
    (minutesToDisembark * oneMinuteInMillis).toLong
  }

  private def actualMinutesToChox(arrival: Arrival): Option[MillisSinceEpoch] =
    arrival.ActualChox.flatMap(c => arrival.Actual.map(a => (c - a) / oneMinuteMillis))

  private def estMinutesToChox(arrival: Arrival): Option[MillisSinceEpoch] =
    arrival.EstimatedChox.flatMap(c => arrival.Actual.map(a => (c - a) / oneMinuteMillis))

  def pcpTimeRange(fws: ApiFlightWithSplits, firstPaxOffMillis: MillisSinceEpoch, walkTimes: WalkTimes, paxFeedSourceOrder: List[FeedSource]): VdomElement =
    fws.apiFlight.PcpTime.map { pcpTime: MillisSinceEpoch =>
      val sdateFrom = SDate(MilliDate(pcpTime))
      val sdateTo = SDate(MilliDate(pcpTime + millisToDisembark(fws.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder).getOrElse(0))))
      val predictedWalkTime = fws.apiFlight.Predictions.predictions.get(WalkTimeModelAndFeatures.targetName).map(c => s"${c / 60}m").getOrElse("-")
      val gateOrStandWalkTime = if (fws.apiFlight.Gate.isDefined || fws.apiFlight.Stand.isDefined) {
        val wtMillis = walkTimes.walkTimeMillisForArrival(0L)(fws.apiFlight.Gate, fws.apiFlight.Stand, fws.apiFlight.Terminal)
        if (wtMillis != 0L) s"${(wtMillis / oneMinuteMillis).toString}m" else "-"
      } else "n/a"

      val postTouchdownTimes = <.span(
        <.h3("Minutes to chox from touchdown"),
        s"DRT predicted: ${fws.apiFlight.Predictions.predictions.get(ToChoxModelAndFeatures.targetName).map(c => s"${c.toString}m").getOrElse("-")}", <.br(),
        s"Feed estimated: ${estMinutesToChox(fws.apiFlight).map(c => s"${c.toString}m").getOrElse("-")}", <.br(),
        s"Feed actual: ${actualMinutesToChox(fws.apiFlight).map(c => s"${c.toString}m").getOrElse("-")}", <.br(),
        <.h3("Other times"),
        s"Chox to doors open: ${firstPaxOffMillis / oneMinuteMillis}m", <.br(),
        s"Predicted walk time: $predictedWalkTime", <.br(),
        s"Actual walk time from gate to arrivals hall: $gateOrStandWalkTime", <.br(),
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

  def uniqueArrivalsWithCodeShares(paxFeedSourceOrder: List[FeedSource]): Seq[ApiFlightWithSplits] => Seq[(ApiFlightWithSplits, Seq[String])] =
    CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => identity(f.apiFlight), paxFeedSourceOrder)
}
