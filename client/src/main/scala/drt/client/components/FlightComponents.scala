package drt.client.components

import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import drt.shared.api.Arrival
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagOf, VdomArray}
import org.scalajs.dom.html.{Div, Span}


object FlightComponents {

  def paxComp(flightWithSplits: ApiFlightWithSplits): TagMod = {
    val isNotApiData = if (flightWithSplits.hasValidApi) "right" else "right notApiData"
    <.div(
      ^.className := s"$isNotApiData",
      Tippy.describe(paxNumberSources(flightWithSplits), flightWithSplits.pcpPaxEstimate)
    )
  }

  def paxClassFromSplits(flightWithSplits: ApiFlightWithSplits): String =
    if (flightWithSplits.apiFlight.Origin.isDomesticOrCta)
      "pax-no-splits"
    else flightWithSplits.bestSplits match {
      case Some(Splits(_, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, _, _)) if flightWithSplits.hasApi => "pax-api"
      case Some(Splits(_, SplitSources.PredictedSplitsWithHistoricalEGateAndFTPercentages, _, _)) => "pax-predicted"
      case Some(Splits(_, SplitSources.Historical, _, _)) => "pax-portfeed"
      case _ => "pax-unknown"
    }

  def paxNumberSources(flight: ApiFlightWithSplits): VdomTagOf[Span] = {
    val max: String = flight.apiFlight.MaxPax.filter(_ > 0).map(_.toString).getOrElse("n/a")
    val portDirectPax: Int = flight.apiFlight.ActPax.getOrElse(0) - flight.apiFlight.TranPax.getOrElse(0)

    val paxNos = List(
      <.p(s"Pax: $portDirectPax (${flight.apiFlight.ActPax.getOrElse(0)} - ${flight.apiFlight.TranPax.getOrElse(0)} transfer)"),
      <.p(s"Max: $max")
    ) :+ flight.maybeApiPaxCount.map(p => <.span(s"API: $p")).getOrElse(EmptyVdom)
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
    flight.MaxPax.filter(_ > 0).map { maxPaxMillis =>
      <.div(^.className := "pax-capacity", ^.width := paxBarWidth(maxFlightPax, maxPaxMillis))
    }.getOrElse {
      VdomArray.empty()
    }
  }

  def paxBarWidth(maxFlightPax: Int, apiPax: Int): String = {
    s"${apiPax.toDouble / maxFlightPax * 100}%"
  }


  def paxTypeAndQueueString(ptqc: PaxTypeAndQueue) = s"${ptqc.displayName}"

  object SplitsGraph {

    case class Props(splitTotal: Int, splits: Iterable[(PaxTypeAndQueue, Int)])

    def splitsGraphComponentColoured(props: Props): TagOf[Div] = {
      import props._
      val maxSplit = props.splits.map(_._2).max
      <.div(
        ^.className := "dashboard-summary__pax",
        <.div(^.className := "dashboard-summary__total-pax", s"${props.splitTotal} Pax"),
        <.div(^.className := "dashboard-summary__splits-graph-bars",
          splits.map {
            case (paxTypeAndQueue, paxCount) =>
              val percentage = ((paxCount.toDouble / maxSplit) * 100).toInt
              val label = paxTypeAndQueueString(paxTypeAndQueue)
              <.div(
                ^.className := s"dashboard-summary__splits-graph-bar dashboard-summary__splits-graph-bar--${paxTypeAndQueue.queueType.toString.toLowerCase}",
                ^.height := s"$percentage%",
                ^.title := s"$label")
          }.toTagMod
        )
      )
    }
  }

  def splitsSummaryTooltip(splits: Seq[(String, Int)]): TagMod = {
    <.table(^.className := "table table-responsive table-striped table-hover table-sm ",
      <.tbody(
        splits.map {
          case (label, paxCount) => <.tr(<.td(s"$paxCount $label"))
        }.toTagMod))
  }
}
