package drt.client.components

import drt.client.SPAMain
import drt.client.components.FlightComponents._
import drt.client.components.FlightComponents.SplitsGraph._
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel
import drt.shared._
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import org.scalajs.dom.raw.HTMLElement

import scala.collection.immutable
import scala.collection.immutable.Seq
import scala.util.Try

object BigSummaryBoxes {
  def flightPcpInPeriod(f: ApiFlightWithSplits, start: SDateLike, end: SDateLike) = {
    val bt: Long = bestTime(f)
    start.millisSinceEpoch <= bt && bt <= end.millisSinceEpoch
  }

  def bestFlightSplitPax(bestFlightPax: (Arrival) => Int): PartialFunction[ApiFlightWithSplits, Double] = {
    case ApiFlightWithSplits(flight, splits) =>
      splits.find { case api@ApiSplits(_, _, t) => t == PaxNumbers } match {
        case None => bestFlightPax(flight)
        case Some(apiSplits) => apiSplits.totalExcludingTransferPax
      }
  }

  def bestTime(f: ApiFlightWithSplits) = {
    val bestTime = {
      val flightDt = SDate.parse(f.apiFlight.SchDT)

      if (f.apiFlight.PcpTime != 0) f.apiFlight.PcpTime else {
        flightDt.millisSinceEpoch
      }
    }
    bestTime
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


  def bestFlightSplits(bestFlightPax: (Arrival) => Int): (ApiFlightWithSplits) => Set[(PaxTypeAndQueue, Double)] = {
    case ApiFlightWithSplits(_, s) if s.isEmpty => Set()
    case ApiFlightWithSplits(flight, splits) =>
      if (splits.exists { case ApiSplits(_, _, t) => t == PaxNumbers }) {
        splits.find { case ApiSplits(_, _, t) => t == PaxNumbers } match {
          case None => Set()
          case Some(apiSplits) => apiSplits.splits.map {
            s => (PaxTypeAndQueue(s.passengerType, s.queueType), s.paxCount)
          }
        }
      } else {
        splits.find { case ApiSplits(_, _, t) => t == Percentage } match {
          case None => Set()
          case Some(apiSplits) => apiSplits.splits.map {
            s => (PaxTypeAndQueue(s.passengerType, s.queueType), s.paxCount / 100 * bestFlightPax(flight))
          }
        }
      }
  }

  def aggregateSplitsLogging(bestFlightPax: (Arrival) => Int)(flights: Seq[ApiFlightWithSplits]) = {
    val flightSplits = bestFlightSplits(bestFlightPax)

    def excludeTrans(s: ((PaxTypeAndQueue), Double)): Boolean = {
      s._1.queueType != Queues.Transfer
    }

    def identity(s: ((PaxTypeAndQueue), Double)): Boolean = true

    def inner(filter: String, sfilter: ((PaxTypeAndQueue, Double)) => Boolean) = {
      val allFlightsBestPaxAndSplitsExTx = flights.map(f => {
        val filter = flightSplits(f).filter(sfilter)
        val splitSum = filter.map(_._2).sum
        (f.apiFlight, bestFlightPax(f.apiFlight), filter, splitSum)
      })
      val notAgreeing = allFlightsBestPaxAndSplitsExTx.filter(f => f._2 != f._3.map(_._2).sum.toInt)
      val allDiffs = notAgreeing.map(f => {
        val splitSum = f._3.map(_._2).sum.toInt
        (Arrival.summaryString(f._1), f._2 - splitSum, f._2, splitSum)
      })
      val totalDiff = allDiffs.map(f => f._2).sum
      println(s"$filter flightPax: arr: ${allFlightsBestPaxAndSplitsExTx.map(_._2).sum} splExTx: ${allFlightsBestPaxAndSplitsExTx.map(_._3.map(_._2).sum).sum}")

      println(s"$filter notAgreeing totalDiff: ${totalDiff} over ${allDiffs.length} ${pprint.stringify(allDiffs)}")
      println(s"notAgreeing: ${pprint.stringify(notAgreeing)}")

    }

    inner("exTx", excludeTrans)
    inner("inTx", identity)

  }

  def aggregateSplits(bestFlightPax: (Arrival) => Int)(flights: Seq[ApiFlightWithSplits]): Map[PaxTypeAndQueue, Int] = {
    val newSplits = Map[PaxTypeAndQueue, Double]()
    val flightSplits = bestFlightSplits(bestFlightPax)
    val allSplits: Seq[(PaxTypeAndQueue, Double)] = flights.flatMap(flightSplits)
    val splitsExcludingTransfers = allSplits.filter(_._1.queueType != Queues.Transfer)
    //    //todo import cats - it makes short, efficient work of this sort of aggregation.
    val aggSplits: Map[PaxTypeAndQueue, Double] = splitsExcludingTransfers.foldLeft(newSplits) {
      case (agg, (k, v)) =>
        val g = agg.getOrElse(k, 0d)
        agg.updated(k, v + g)
    }
    val aggSplitsInts: Map[PaxTypeAndQueue, Int] = aggSplits.mapValues(Math.round(_).toInt)

    aggSplitsInts
  }

  def convertMapToAggSplits(aggSplits: Map[PaxTypeAndQueue, Double]) = ApiSplits(
    aggSplits.map {
      case (k, v) => {
        ApiPaxTypeAndQueueCount(k.passengerType, k.queueType, v)
      }
    }.toSet,
    "Aggregated", PaxNumbers
  )

  def flightsAtTerminal(flightsPcp: immutable.Seq[ApiFlightWithSplits], ourTerminal: String) = {
    flightsPcp.filter(f => f.apiFlight.Terminal == ourTerminal)
  }

  def sumActPax(flights: Seq[ApiFlightWithSplits]) = flights.map(_.apiFlight.ActPax).sum

  def sumBestPax(bestFlightSplitPax: (ApiFlightWithSplits) => Double)(flights: Seq[ApiFlightWithSplits]) = flights.map(bestFlightSplitPax).sum

  case class Props(flightCount: Int, actPaxCount: Int, bestPaxCount: Int, aggSplits: Map[PaxTypeAndQueue, Int], paxQueueOrder: Seq[PaxTypeAndQueue])


  def GraphComponent(source: String, splitStyleUnitLabe: String, sourceDisplay: String, splitTotal: Int, queuePax: Map[PaxTypeAndQueue, Int], paxQueueOrder: Seq[PaxTypeAndQueue]) = {
    val value = Try {
      val orderedSplitCounts: Seq[(PaxTypeAndQueue, Int)] = paxQueueOrder.map(ptq => ptq -> queuePax.getOrElse(ptq, 0))
      val tt: TagMod = <.table(^.className := "table table-responsive table-striped table-hover table-sm ",
        <.tbody(
          orderedSplitCounts.map { s =>
            <.tr(<.td(s._1.passengerType.name), <.td(s._1.queueType), <.td(s._2))
          }.toTagMod))

      val nbsp = "\u00a0"
      <.h3(
        nbsp,
        <.span(
          // summary-box-count best-pax-count are here as a dirty hack for alignment with the other boxes
          <.div(^.className := "summary-box-count best-pax-count split-graph-container splitsource-" + source,
            SplitsGraph.splitsGraphComponentColoured(SplitsGraph.Props(splitTotal, orderedSplitCounts, tt)), sourceDisplay))
      )
    }
    val g: Try[TagOf[HTMLElement]] = value recoverWith {
      case f => Try(<.div(f.toString))
    }
    g.get
  }


  val SummaryBox = ScalaComponent.builder[Props]("SummaryBox")
    .render_P((p) => {

      <.div(^.className := "summary-boxes ",
        <.div(^.className := "summary-box-container", <.h3(<.span(^.className := "summary-box-count flight-count", f"${p.flightCount}%,d"), <.span(^.className := "sub", " Flights"))),
        <.div(^.className := "summary-box-container", <.h3(<.span(^.className := "summary-box-count best-pax-count", f"${p.bestPaxCount}%,d "), <.span(^.className := "sub", " Best Pax"))),
        <.div(^.className := "summary-box-container", GraphComponent("aggregated", "pax", "", p.aggSplits.values.sum, p.aggSplits, p.paxQueueOrder)))
    })
    .build
}
