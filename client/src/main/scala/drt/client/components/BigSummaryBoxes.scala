package drt.client.components

import drt.client.components.FlightComponents.splitsGraphComponent
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.html_<^._

import scala.collection.immutable
import scala.collection.immutable.Seq

object BigSummaryBoxes {
  def flightPcpInPeriod(f: ApiFlightWithSplits, now: SDateLike, nowPlus3Hours: SDateLike) = {
    val bestTime = {
      val flightDt = SDate.parse(f.apiFlight.SchDT)
      println(s"now: ${now.toString} == ${now.millisSinceEpoch} nowPlus: ${nowPlus3Hours.toString} flightDt: ${flightDt} == ${flightDt.millisSinceEpoch} pcpTime: ${f.apiFlight.PcpTime}")

      if (f.apiFlight.PcpTime != 0) f.apiFlight.PcpTime else {
        flightDt.millisSinceEpoch
      }
    }
    now.millisSinceEpoch <= bestTime && bestTime <= nowPlus3Hours.millisSinceEpoch
  }

  def flightsInPeriod(flights: Seq[ApiFlightWithSplits], now: SDateLike, nowPlus3Hours: SDateLike) = {
    flights.filter(flightPcpInPeriod(_, now, nowPlus3Hours))
  }

  def countFlightsInPeriod(rootModel: RootModel, now: SDateLike, nowPlus3Hours: SDateLike) = {
    rootModel.flightsWithSplitsPot.map(splits => flightsInPeriod(splits.flights, now, nowPlus3Hours).length)
  }

  def countPaxInPeriod(rootModel: RootModel, now: SDateLike, nowPlus3Hours: SDateLike) = {
    rootModel.flightsWithSplitsPot.map(splits => {
      val flights: Seq[ApiFlightWithSplits] = flightsInPeriod(splits.flights, now, nowPlus3Hours)
      sumPax(flights)
    })
  }


  def aggregateSplits(flights: Seq[ApiFlightWithSplits]) = {
    val newSplits = Map[PaxTypeAndQueue, Int]()
    val map = flights.flatMap(f => f.splits)
    println(s"flatMapped $map")
    //todo import cats
    val allSplits: immutable.Seq[ApiPaxTypeAndQueueCount] = map.map(_.splits).flatten
    println(s"flattened $allSplits")
    val aggSplits: Map[PaxTypeAndQueue, Int] = allSplits.foldLeft(newSplits) { (agg, s) =>
      println(s"folding $agg $s")
      val k = PaxTypeAndQueue(s.passengerType, s.queueType)
      val g = agg.getOrElse(k, 0)
      agg.updated(k, s.paxCount.toInt + g)
    }
    aggSplits
  }

  def convertMapToAggSplits(aggSplits: Map[PaxTypeAndQueue, Double]) = ApiSplits(
    aggSplits.map {
      case (k, v) => {
        ApiPaxTypeAndQueueCount(k.passengerType, k.queueType, v)
      }
    }.toList,
    "Aggregated", PaxNumbers
  )

  def flightsAtTerminal(flightsPcp: immutable.Seq[ApiFlightWithSplits], ourTerminal: String) = {
    flightsPcp.filter(f => f.apiFlight.Terminal == ourTerminal)
  }

  def sumPax(flights: Seq[ApiFlightWithSplits]) = flights.map(_.apiFlight.ActPax).sum

  case class Props(flightCount: Int, actPaxCount: Int, aggSplits: Map[PaxTypeAndQueue, Int])


  def GraphComponent(source: String, splitStyleUnitLabe: String, sourceDisplay: String, splitTotal: Int, queuePax: Map[PaxTypeAndQueue, Int]) = {
    val orderedSplitCounts: Seq[(PaxTypeAndQueue, Int)] = PaxTypesAndQueues.inOrder.map(ptq => ptq -> queuePax.getOrElse(ptq, 0))
    val splitsAndLabels: Seq[(String, Int)] = orderedSplitCounts.map {
      case (ptqc, paxCount) => (s"$splitStyleUnitLabe ${ptqc.passengerType} > ${ptqc.queueType}", paxCount)
    }
    val nbsp = "\u00a0"
    <.h3(
      nbsp,
      <.div(^.className := "split-graph-container splitsource-" + source,
        splitsGraphComponent(splitTotal, splitsAndLabels), sourceDisplay))
  }


  val SummaryBox = ScalaComponent.builder[Props]("SummaryBox")
    .render_P((p) =>
      <.div(^.className := "summary-boxes",
        <.div(^.className := "summary-box-container", <.h3(<.span(^.className := "summary-box-count flight-count", p.flightCount), <.span(^.className := "sub", "Flights"))),
        <.div(^.className := "summary-box-container", <.h3(<.span(^.className := "summary-box-count act-pax-count", p.actPaxCount), <.span(^.className := "sub", "Pax"))),
        <.div(^.className := "summary-box-container",
          GraphComponent("aggregated", "pax", "", p.aggSplits.values.sum, p.aggSplits)
        )

      ))
    .build
}
