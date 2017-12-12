package drt.client.components

import drt.client.services.SPACircuit
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagOf, VdomArray}
import org.scalajs.dom.html.Div


object FlightComponents {

  def paxComp(maxFlightPax: Int = 853)(flightWithSplits: ApiFlightWithSplits): TagMod = {

    val flight = flightWithSplits.apiFlight
    val apiSplits = flightWithSplits.apiSplits.getOrElse(ApiSplits(Set(), "no splits - client", None))
    val apiPax: Int = ApiSplits.totalPax(apiSplits.splits).toInt
    val apiExTransPax: Int = ApiSplits.totalExcludingTransferPax(apiSplits.splits).toInt

    val airportConfigRCP = SPACircuit.connect(_.airportConfig)
    airportConfigRCP(acPot => {
      <.div(
        acPot().renderReady(_ => {
          val paxToDisplay: Int = ArrivalHelper.bestPax(flight)
          val paxWidth = paxBarWidth(maxFlightPax, paxToDisplay)
          val paxClass = paxClassFromSplits(flightWithSplits)
          val maxCapLine = maxCapacityLine(maxFlightPax, flight)

          <.div(
            ^.title := paxComponentTitle(flight, apiExTransPax, apiPax),
            ^.className := "pax-cell",
            maxCapLine,
            <.div(^.className := paxClass, ^.width := paxWidth),
            <.div(^.className := "pax", paxToDisplay),
            maxCapLine)
        }))
    })
  }

  def paxClassFromSplits(flightWithSplits: ApiFlightWithSplits): String = {
    flightWithSplits.bestSplits match {
      case Some(ApiSplits(_, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, _, _)) => "pax-api"
      case Some(ApiSplits(_, SplitSources.Historical, _, _)) => "pax-portfeed"
      case _ => "pax-unknown"
    }
  }

  def bestPaxToDisplay(flight: Arrival, apiExTransPax: Int, portCode: String): Int = {
    val bestNonApiPax = ArrivalHelper.bestPax(flight)
    val apiDiffTrustThreshold = 0.2
    val absPercentageDifference = Math.abs(apiExTransPax - bestNonApiPax).toDouble / bestNonApiPax
    val trustApi = absPercentageDifference <= apiDiffTrustThreshold
    val paxToDisplay = if (apiExTransPax > 0 && trustApi) apiExTransPax else bestNonApiPax
    paxToDisplay
  }

  def paxComponentTitle(flight: Arrival, apiPax: Int, apiIncTrans: Int): String = {
    val max: String = if (flight.MaxPax > 0) flight.MaxPax.toString else "n/a"
    val portDirectPax: Int = flight.ActPax - flight.TranPax
    s"""
       |Port: $portDirectPax (${flight.ActPax} - ${flight.TranPax} transfer)
       |Max: $max""".stripMargin
  }

  def maxCapacityLine(maxFlightPax: Int, flight: Arrival): TagMod = {
    if (flight.MaxPax > 0)
      <.div(^.className := "pax-capacity", ^.width := paxBarWidth(maxFlightPax, flight.MaxPax))
    else
      VdomArray.empty()
  }

  def paxBarWidth(maxFlightPax: Int, apiPax: Int): String = {
    s"${apiPax.toDouble / maxFlightPax * 100}%"
  }


  def paxTypeAndQueueString(ptqc: PaxTypeAndQueue) = s"${ptqc.passengerType} > ${ptqc.queueType}"

  object SplitsGraph {

    case class Props(splitTotal: Int, splits: Seq[(PaxTypeAndQueue, Int)], tooltipOption: Option[TagMod])

    def splitsGraphComponentColoured(props: Props): TagOf[Div] = {
      import props._
      <.div(^.className := "splits",
        tooltipOption.map(tooltip =>
          <.div(^.className := "splits-tooltip", <.div(tooltip))).toList.toTagMod,
        <.div(^.className := "graph",
          <.div(^.className := "bars",
            splits.map {
              case (paxTypeAndQueue, paxCount) =>
                val percentage: Double = paxCount.toDouble / splitTotal * 100
                val label = paxTypeAndQueueString(paxTypeAndQueue)
                <.div(
                  ^.className := "bar " + paxTypeAndQueue.queueType,
                  ^.height := s"$percentage%",
                  ^.title := s"$paxCount $label")
            }.toTagMod,
            <.div(^.className := "bar-max bar")
          )))
    }
  }

  def splitsSummaryTooltip(splitTotal: Int, splits: Seq[(String, Int)]): TagMod = {
    <.table(^.className := "table table-responsive table-striped table-hover table-sm ",
      <.tbody(
        splits.map {
          case (label, paxCount) => <.tr(<.td(s"$paxCount $label"))
        }.toTagMod))
  }
}
