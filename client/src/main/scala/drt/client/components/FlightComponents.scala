package drt.client.components

import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagOf, VdomArray}
import org.scalajs.dom.html.Div


object FlightComponents {

  def paxComp(flightWithSplits: ApiFlightWithSplits): TagMod = <.div(
    ^.title := paxComponentTitle(flightWithSplits.apiFlight),
    ^.className := "right",
    ArrivalHelper.bestPax(flightWithSplits.apiFlight)
  )

  def paxClassFromSplits(flightWithSplits: ApiFlightWithSplits): String = flightWithSplits.bestSplits match {
    case Some(Splits(_, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, _, _)) => "pax-api"
    case Some(Splits(_, SplitSources.PredictedSplitsWithHistoricalEGateAndFTPercentages, _, _)) => "pax-predicted"
    case Some(Splits(_, SplitSources.Historical, _, _)) => "pax-portfeed"
    case _ => "pax-unknown"
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

    case class Props(splitTotal: Int, splits: Seq[(PaxTypeAndQueue, Int)])

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
          }.toTagMod,
          <.div(^.className := "dashboard-summary__splits-graph-bar dashboard-summary__splits-graph-bar--max")
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
