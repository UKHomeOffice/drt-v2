package drt.client.components

import drt.client.services.SPACircuit
import drt.shared._
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagOf, VdomArray}
import org.scalajs.dom.html.Div


object FlightComponents {

  def paxComp(maxFlightPax: Int = 853)(flight: Arrival, apiSplits: ApiSplits): TagMod = {

    val airportConfigRCP = SPACircuit.connect(_.airportConfig)

    val apiPax: Int = ApiSplits.totalPax(apiSplits.splits).toInt
    val apiExTransPax: Int = ApiSplits.totalExcludingTransferPax(apiSplits.splits).toInt

    airportConfigRCP(acPot => {
      <.div(
        acPot().renderReady(ac => {
          val paxToDisplay: Int = bestPaxToDisplay(flight, apiExTransPax, ac.portCode)
          val paxWidth = paxBarWidth(maxFlightPax, paxToDisplay)
          val paxClass = paxDisplayClass(flight, apiPax, paxToDisplay)
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

  def bestPaxToDisplay(flight: Arrival, apiExTransPax: Int, portCode: String) = {
    val bestNonApiPax = BestPax()(flight)
    val apiDiffTrustThreshold = 0.2
    val absPercentageDifference = Math.abs(apiExTransPax - bestNonApiPax).toDouble / bestNonApiPax
    val trustApi = absPercentageDifference <= apiDiffTrustThreshold
    val paxToDisplay = if (apiExTransPax > 0 && trustApi) apiExTransPax else bestNonApiPax
    paxToDisplay
  }

  def paxDisplayClass(flight: Arrival, apiPax: Int, paxToDisplay: Int) = {
    if (apiPax > 0) {
      "pax-api"
    } else if (paxToDisplay == flight.ActPax) {
      "pax-portfeed"
    } else {
      "pax-unknown"
    }
  }

  def paxComponentTitle(flight: Arrival, apiPax: Int, apiIncTrans: Int): String = {
    val api: String = if (apiPax > 0) apiPax.toString else "n/a"
    val port: String = if (flight.ActPax > 0) flight.ActPax.toString else "n/a"
    val last: String = flight.LastKnownPax.getOrElse("n/a").toString
    val max: String = if (flight.MaxPax > 0) flight.MaxPax.toString else "n/a"
    val portDirectPax: Int = flight.ActPax - flight.TranPax
    s"""
       |API: ${api} = (${apiIncTrans} - ${apiIncTrans - apiPax} transfer)
       |Port: ${portDirectPax} (${flight.ActPax} - ${flight.TranPax} transfer)
       |Previous: ${last}
       |Max: ${max}
                  """.stripMargin
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
    case class Props(splitTotal: Int, splits: Seq[(PaxTypeAndQueue, Int)], tooltip: TagMod)
    def splitsGraphComponentColoured(props: Props): TagOf[Div] = {
      import props._
      <.div(^.className := "splits",
        <.div(^.className := "splits-tooltip", <.div(tooltip)),
        <.div(^.className := "graph",
          splits.map {
            case (paxTypeAndQueue, paxCount) =>
              val percentage: Double = paxCount.toDouble / splitTotal * 100
              val label = paxTypeAndQueueString(paxTypeAndQueue)
              <.div(
                ^.className := "bar " + paxTypeAndQueue.queueType,
                ^.height := s"${percentage}%",
                ^.title := s"$paxCount $label")
          }.toTagMod
        ))
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
