package drt.client.components

import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import drt.shared.api.Arrival
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagOf, VdomArray}
import org.scalajs.dom.html.Div


object FlightComponents {

  def paxComp(flightWithSplits: ApiFlightWithSplits): TagMod = {
    val isNotApiData = if (isLiveAndApiPaxCountWithinThreshold(flightWithSplits)) "right notApiData" else "right"
    <.div(
      ^.title := paxComponentTitle(flightWithSplits.apiFlight),
      ^.className := s"$isNotApiData",
      flightWithSplits.apiFlight.bestPaxEstimate
    )
  }

  def paxClassFromSplits(flightWithSplits: ApiFlightWithSplits): String =
    if (flightWithSplits.apiFlight.Origin.isDomesticOrCta)
      "pax-no-splits"
    else flightWithSplits.bestSplits match {
      case Some(Splits(_, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, _, _)) => "pax-api"
      case Some(Splits(_, SplitSources.PredictedSplitsWithHistoricalEGateAndFTPercentages, _, _)) => "pax-predicted"
      case Some(Splits(_, SplitSources.Historical, _, _)) => "pax-portfeed"
      case _ => "pax-unknown"
    }


  def isLiveAndApiPaxCountWithinThreshold(flightWithSplits: ApiFlightWithSplits, threshold: Double = 0.05) : Boolean = {
    if (flightWithSplits.apiFlight.FeedSources.contains(LiveFeedSource)) {
    val apiSplits = flightWithSplits.splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages)
    val paxCount: Double = apiSplits.map(_.splits.toList.map(_.paxCount).sum).getOrElse(0)
    val isValidThreshold = paxCount != 0 && Math.abs(paxCount - flightWithSplits.apiFlight.ActPax.getOrElse(0)) / paxCount < threshold
      if (isValidThreshold) true else false
    } else {
      true
    }
  }

  def hasApiSplits(flightWithSplits: ApiFlightWithSplits): Boolean = flightWithSplits.bestSplits match {
    case Some(Splits(_, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, _, _)) if isLiveAndApiPaxCountWithinThreshold(flightWithSplits) => true
    case _ => false
  }

  def paxComponentTitle(flight: Arrival): String = {
    val max: String = flight.MaxPax.filter(_ > 0).map(_.toString).getOrElse("n/a")
    val portDirectPax: Int = flight.ActPax.getOrElse(0) - flight.TranPax.getOrElse(0)
    val apiPax = flight.ApiPax match {
      case Some(api) =>
        s"\nAPI: $api"
      case _ => ""
    }
    s"""|Pax: $portDirectPax (${flight.ActPax.getOrElse(0)} - ${flight.TranPax.getOrElse(0)} transfer)
        |Max: $max $apiPax""".stripMargin
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


  def paxTypeAndQueueString(ptqc: PaxTypeAndQueue) = s"${PaxTypesAndQueues.displayName(ptqc)}"

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
