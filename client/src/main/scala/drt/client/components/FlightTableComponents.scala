package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagMod, TagOf}
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
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

  def pcpTimeRange(fws: ApiFlightWithSplits): TagOf[Div] =
    fws.apiFlight.PcpTime.map { pcpTime: MillisSinceEpoch =>
      val sdateFrom = SDate(MilliDate(pcpTime))
      val sdateTo = SDate(MilliDate(pcpTime + millisToDisembark(fws.pcpPaxEstimate)))
      <.div(^.display := "flex", ^.flexWrap := "nowrap",
        sdateLocalTimePopup(sdateFrom),
        " \u2192 ",
        sdateLocalTimePopup(sdateTo)
      )
    } getOrElse {
      <.div()
    }

  def sdateLocalTimePopup(sdate: SDateLike): Unmounted[Tippy.Props, Unit, Unit] = {
    val hhmm = f"${sdate.getHours()}%02d:${sdate.getMinutes()}%02d"
    Tippy.describe(<.span(sdate.toLocalDateTimeString(), ^.display := "inline"), hhmm)
  }

  val uniqueArrivalsWithCodeShares: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])] = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => identity(f.apiFlight))
}
