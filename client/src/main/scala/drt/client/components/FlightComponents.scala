package drt.client.components

import diode.UseValueEq
import drt.shared.redlist.DirectRedListFlight
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.TrendingFlat
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagOf, VdomArray}
import org.scalajs.dom.html.{Div, Span}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, TotalPaxSource}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.ports._


object FlightComponents {
  def paxFeedSourceClass(paxSource: TotalPaxSource): String = (paxSource.feedSource) match {
    case (ApiFeedSource) => "pax-rag-green"
    case (LiveFeedSource) => "pax-rag-green"
    case (HistoricApiFeedSource) => "pax-rag-amber"
    case (ForecastFeedSource) => "pax-rag-amber"
    case (ApiFeedSource) => "pax-rag-red"
    case (AclFeedSource) => "pax-rag-red"
    case _ => "pax-rag-red"
  }

  def paxComp(flightWithSplits: ApiFlightWithSplits, directRedListFlight: DirectRedListFlight, noPcpPax: Boolean): TagMod = {
    val isNotApiData = if (flightWithSplits.hasValidApi) "" else "notApiData"
    val noPcpPaxClass = if (noPcpPax || directRedListFlight.outgoingDiversion) "arrivals__table__flight__no-pcp-pax" else ""

    val diversionClass =
      if (directRedListFlight.incomingDiversion) "arrivals__table__flight__pcp-pax__incoming"
      else if (directRedListFlight.outgoingDiversion) "arrivals__table__flight__pcp-pax__outgoing"
      else ""

    <.div(
      ^.className := s"right arrivals__table__flight__pcp-pax $diversionClass $isNotApiData",

      <.span(Tippy.describe(paxNumberSources(flightWithSplits), <.span(^.className := s"$noPcpPaxClass", flightWithSplits.pcpPaxEstimate.pax))),
      if (directRedListFlight.paxDiversion) {
        val incomingTip =
          if (directRedListFlight.incomingDiversion) s"Passengers diverted from ${flightWithSplits.apiFlight.Terminal}"
          else "Passengers diverted to red list terminal"
        Tippy.describe(<.span(incomingTip), MuiIcons(TrendingFlat)())
      } else <.span(),
    )
  }

  def paxClassFromSplits(flightWithSplits: ApiFlightWithSplits): String = {
    if (flightWithSplits.apiFlight.Origin.isDomesticOrCta)
      "pax-no-splits"
    else flightWithSplits.bestSplits.map(_.source) match {
      case Some(SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages) if flightWithSplits.hasApi => "pax-rag-green"
      case Some(SplitSources.Historical) => "pax-rag-amber"
      case _ => "pax-rag-red"
    }
  }

  def paxNumberSources(flight: ApiFlightWithSplits): VdomTagOf[Span] = {
    val max: String = flight.apiFlight.MaxPax.filter(_ > 0).map(_.toString).getOrElse("n/a")
    val portDirectPax: Int = flight.apiFlight.ActPax.getOrElse(0) - flight.apiFlight.TranPax.getOrElse(0)

    val paxNos = List(
      <.p(s"Pax: $portDirectPax (${flight.apiFlight.ActPax.getOrElse(0)} - ${flight.apiFlight.TranPax.getOrElse(0)} transfer)"),
      <.p(s"Max: $max")
    ) :+ flight.totalPaxFromApiExcludingTransfer.map(p => <.span(s"API: ${p.pax}")).getOrElse(EmptyVdom)
    <.span(paxNos.toVdomArray)
  }

  def paxTransferComponent(flight: Arrival): VdomTagOf[Div] = {
    val transPax = if (flight.Origin.isCta) "-" else flight.TranPax.getOrElse("-")
    <.div(
      ^.className := "right",
      s"$transPax"
    )
  }

  def maxCapacityLine(maxFlightPax: Int, flight: Arrival): TagMod = {
    flight.MaxPax.filter(_ > 0)
      .map(maxPaxMillis => <.div(^.className := "pax-capacity", ^.width := paxBarWidth(maxFlightPax, maxPaxMillis)))
      .getOrElse(VdomArray.empty())
  }

  def paxBarWidth(maxFlightPax: Int, apiPax: Int): String =
    s"${apiPax.toDouble / maxFlightPax * 100}%"

  def paxTypeAndQueueString(ptqc: PaxTypeAndQueue) = s"${ptqc.displayName}"

  object SplitsGraph {

    case class Props(splitTotal: Int, splits: Iterable[(PaxTypeAndQueue, Int)]) extends UseValueEq

    def splitsGraphComponentColoured(props: Props): TagOf[Div] = {
      import props._
      val maxSplit = props.splits.map(_._2).max
      <.div(
        ^.className := "dashboard-summary__pax",
        <.div(^.className := "dashboard-summary__total-pax", s"${props.splitTotal} Pax"),
        <.div(^.className := "dashboard-summary__splits-graph-bars", ^.tabIndex := 0,
          splits.map {
            case (paxTypeAndQueue, paxCount) =>
              val percentage = ((paxCount.toDouble / maxSplit) * 100).toInt
              val label = paxTypeAndQueueString(paxTypeAndQueue)
              <.div(^.className := s"dashboard-summary__splits-graph-bar dashboard-summary__splits-graph-bar--${paxTypeAndQueue.queueType.toString.toLowerCase}",
                ^.height := s"$percentage%",
                ^.title := s"$label",
                ^.aria.label := s"$label passenger percentage is $percentage.",
                ^.tabIndex := 0
              )
          }.toTagMod
        )
      )
    }
  }

  def splitsSummaryTooltip(splits: Seq[(String, Int)]): TagMod = {
    <.table(^.className := "table table-flex table-responsive table-striped table-hover table-sm ",
      <.tbody(
        splits.map {
          case (label, paxCount) => <.tr(<.td(s"$paxCount $label"))
        }.toTagMod))
  }
}
