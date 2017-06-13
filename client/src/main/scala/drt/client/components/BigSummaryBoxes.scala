package drt.client.components

import drt.client.components.FlightComponents._
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import org.scalajs.dom.raw.HTMLElement

import scala.collection.immutable
import scala.collection.immutable.Seq
import scala.util.Try

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

  def flightsInPeriod(flights: Seq[ApiFlightWithSplits], now: SDateLike, nowPlus3Hours: SDateLike) =
    flights.filter(flightPcpInPeriod(_, now, nowPlus3Hours))

  def countFlightsInPeriod(rootModel: RootModel, now: SDateLike, nowPlus3Hours: SDateLike) =
    rootModel.flightsWithSplitsPot.map(splits => flightsInPeriod(splits.flights, now, nowPlus3Hours).length)

  def countPaxInPeriod(rootModel: RootModel, now: SDateLike, nowPlus3Hours: SDateLike) = {
    rootModel.flightsWithSplitsPot.map(splits => {
      val flights: Seq[ApiFlightWithSplits] = flightsInPeriod(splits.flights, now, nowPlus3Hours)
      sumActPax(flights)
    })
  }


  def bestFlightPax(f: Arrival) = {
    if (f.ActPax > 0) f.ActPax
    else f.MaxPax
  }

  def aggregateSplits(flights: Seq[ApiFlightWithSplits]) = {
    val newSplits = Map[PaxTypeAndQueue, Int]()
    println(s"flightDiff start")
    val allSplits: Seq[(PaxTypeAndQueue, Double)] = flights.flatMap {
      case ApiFlightWithSplits(f, Nil) =>
        println(s"No splits for $f")
        Nil
      case ApiFlightWithSplits(f, headSplit :: ss) if headSplit.splitStyle == PaxNumbers =>
        headSplit.splits.map {
          s =>
            val splitTotal = headSplit.totalPax
            val paxDelta = splitTotal - bestFlightPax(f)
            println(s"flightDiff ${splitTotal} vs ${bestFlightPax(f)} $paxDelta")
            (PaxTypeAndQueue(s.passengerType, s.queueType), s.paxCount)
        }
      case ApiFlightWithSplits(f, headSplit :: ss) if headSplit.splitStyle == Percentage =>
        val splits = headSplit.splits
        val splitsTotal = headSplit.totalPax
        println(s"flightDiff pct $splitsTotal")
        splits.map {
          s => {
            println(s"a pct split $s of $splitsTotal ")
            (PaxTypeAndQueue(s.passengerType, s.queueType), s.paxCount / 100 * bestFlightPax(f))
          }
        }
    }
    //    println(s"flatMapped $map")
    //    //todo import cats
    //    val allSplits: immutable.Seq[ApiPaxTypeAndQueueCount] = map.map(_.splits).flatten
    println(s"flightDiff end")
    println(allSplits.map("flattened" + _).mkString("\n"))
    val totalPax = allSplits.map(_._2).sum
    println(s"flattened total $totalPax on ${allSplits.length} splits over ${flights.length} flights.  Max: ${flights.map(_.apiFlight.MaxPax).sum} Act: ${flights.map(_.apiFlight.ActPax).sum}")
    val aggSplits: Map[PaxTypeAndQueue, Int] = allSplits.foldLeft(newSplits) {
      case (agg, s@(k, v)) =>
        println(s"folding $agg $s")
        val g = agg.getOrElse(k, 0)
        agg.updated(k, v.toInt + g)
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

  def sumActPax(flights: Seq[ApiFlightWithSplits]) = flights.map(_.apiFlight.ActPax).sum

  def bestFlightSplitPax: PartialFunction[ApiFlightWithSplits, Double] = {
    case ApiFlightWithSplits(_, (h@ApiSplits(_, _, PaxNumbers)) :: ss) => h.totalPax
    case ApiFlightWithSplits(flight, _) => bestFlightPax(flight)
  }

  def sumBestPax(flights: Seq[ApiFlightWithSplits]) = flights.map(bestFlightSplitPax).sum

  case class Props(flightCount: Int, actPaxCount: Int, bestPaxCount: Int, aggSplits: Map[PaxTypeAndQueue, Int])


  def GraphComponent(source: String, splitStyleUnitLabe: String, sourceDisplay: String, splitTotal: Int, queuePax: Map[PaxTypeAndQueue, Int]) = {
    val value = Try {
      val orderedSplitCounts: Seq[(PaxTypeAndQueue, Int)] = PaxTypesAndQueues.inOrder.map(ptq => ptq -> queuePax.getOrElse(ptq, 0))
      val nbsp = "\u00a0"
      <.h3(
        nbsp,
        <.span(
          <.div(^.className := "split-graph-container splitsource-" + source,
            splitsGraphComponentColoure(splitTotal, orderedSplitCounts), sourceDisplay))
      )
    }
    val g: Try[TagOf[HTMLElement]] = value recoverWith {
      case f => Try(<.div(f.toString))
    }
    g.get
  }


  val SummaryBox = ScalaComponent.builder[Props]("SummaryBox")
    .render_P((p) => {
      <.div(^.className := "summary-boxes",
        <.div(^.className := "summary-box-container", <.h3(<.span(^.className := "summary-box-count flight-count", f"${p.flightCount}%,d "), <.span(^.className := "sub", "Flights"))),
        <.div(^.className := "summary-box-container", <.h3(<.span(^.className := "summary-box-count best-pax-count", f"${p.bestPaxCount}%,d "), <.span(^.className := "sub", "Best Pax"))),
        <.div(^.className := "summary-box-container", GraphComponent("aggregated", "pax", "", p.aggSplits.values.sum, p.aggSplits)))
    })
    .build
}
