package drt.client.components

import drt.client.services.SPACircuit
import drt.shared._
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagOf, VdomArray}
import org.scalajs.dom.html.Div

import scala.collection.immutable.Seq

object FlightComponents {

  def paxComp(maxFlightPax: Int = 853)(flight: Arrival, apiSplits: ApiSplits): TagMod = {

    val airportConfigRCP = SPACircuit.connect(_.airportConfig)

    val apiPax: Int = apiSplits.splits.map(_.paxCount.toInt).sum

    airportConfigRCP(acPot => {
      <.div(
        acPot().renderReady(ac => {
          val (paxNos, paxClass, paxWidth) = if (apiPax > 0)
            (apiPax, "pax-api", paxBarWidth(maxFlightPax, apiPax))
          else
            (flight.ActPax, "pax-portfeed", paxBarWidth(maxFlightPax, BestPax(ac.portCode)(flight)))

          val maxCapLine = maxCapacityLine(maxFlightPax, flight)

          <.div(
            ^.title := paxComponentTitle(flight, apiPax),
            ^.className := "pax-cell",
            maxCapLine,
            <.div(^.className := paxClass, ^.width := paxWidth),
            <.div(^.className := "pax", paxNos),
            maxCapLine)
        }))
    })
  }

  def paxComponentTitle(flight: Arrival, apiPax: Int): String = {
    val api: Any = if (apiPax > 0) apiPax else "n/a"
    val port: Any = if (flight.ActPax > 0) flight.ActPax else "n/a"
    val max: Any = if (flight.MaxPax > 0) flight.MaxPax else "n/a"
    s"""
       |API: ${api}
       |Port: ${port}
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

  def splitsGraphComponent(splitTotal: Int, splits: Seq[(String, Int)]): TagOf[Div] = {
    <.div(^.className := "splits", ^.title := splitsSummaryTooltip(splitTotal, splits),
      <.div(^.className := "graph",
        splits.map {
          case (label, paxCount) =>
            val percentage: Double = paxCount.toDouble / splitTotal * 100
            <.div(
              ^.className := "bar",
              ^.height := s"${percentage}%",
              ^.title := s"$paxCount $label")
        }.toTagMod
      ))
  }

  def paxTypeAndQueueString(ptqc: PaxTypeAndQueue) = s"${ptqc.passengerType} > ${ptqc.queueType}"

  def splitsGraphComponentColoure(splitTotal: Int, splits: Seq[(PaxTypeAndQueue, Int)]): TagOf[Div] = {
    <.div(^.className := "splits", ^.title := splitsSummaryTooltip(splitTotal, splits.map{case (k, v) => (paxTypeAndQueueString(k), v)}),
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

  def splitsSummaryTooltip(splitTotal: Int, splits: Seq[(String, Int)]): String = {
    val totalLine = s"Total: ${splits.map(_._2).sum}\n"
    val splitLines = splits.map {
      case (label, paxCount) =>
        s"$paxCount $label"
    }.mkString("\n")
    totalLine + splitLines
  }
}
