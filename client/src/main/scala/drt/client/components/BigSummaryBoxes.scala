package drt.client.components

import diode.data.Pot
import drt.client.components.FlightComponents._
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared._
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import org.scalajs.dom.raw.HTMLElement

import scala.util.Try

object BigSummaryBoxes {
  def flightPcpInPeriod(f: ApiFlightWithSplits, start: SDateLike, end: SDateLike): Boolean = {
    val bt: Long = bestTime(f)
    start.millisSinceEpoch <= bt && bt <= end.millisSinceEpoch
  }

  def bestFlightSplitPax(bestFlightPax: Arrival => Int): PartialFunction[ApiFlightWithSplits, Double] = {
    case ApiFlightWithSplits(flight, splits, _) =>
      splits.find { case Splits(_, _, _, t) => t == PaxNumbers } match {
        case None => bestFlightPax(flight)
        case Some(apiSplits) => apiSplits.totalExcludingTransferPax
      }
  }

  def bestTime(f: ApiFlightWithSplits): MillisSinceEpoch = {
    val bestTime = {
      val flightDt = SDate(f.apiFlight.Scheduled)

      f.apiFlight.PcpTime.getOrElse(flightDt.millisSinceEpoch)
    }
    bestTime
  }

  def flightsInPeriod(flights: Seq[ApiFlightWithSplits], now: SDateLike, nowPlus3Hours: SDateLike): Seq[ApiFlightWithSplits] =
    flights.filter(flightPcpInPeriod(_, now, nowPlus3Hours))

  def countFlightsInPeriod(rootModel: RootModel, now: SDateLike, nowPlus3Hours: SDateLike): Pot[Int] =
    rootModel.portStatePot.map(portState => flightsInPeriod(portState.flights.values.toList, now, nowPlus3Hours).length)

  def countPaxInPeriod(rootModel: RootModel, now: SDateLike, nowPlus3Hours: SDateLike): Pot[Int] = {
    rootModel.portStatePot.map(portState => {
      val flights: Seq[ApiFlightWithSplits] = flightsInPeriod(portState.flights.values.toList, now, nowPlus3Hours)
      sumActPax(flights)
    })
  }

  def bestFlightSplits(bestFlightPax: Arrival => Int): ApiFlightWithSplits => Set[(PaxTypeAndQueue, Double)] = {
    case ApiFlightWithSplits(_, s, _) if s.isEmpty => Set()
    case ApiFlightWithSplits(flight, splits, _) =>
      if (splits.exists { case Splits(_, _, _, t) => t == PaxNumbers }) {
        splits.find { case Splits(_, _, _, t) => t == PaxNumbers } match {
          case None => Set()
          case Some(apiSplits) => apiSplits.splits.map {
            s => (PaxTypeAndQueue(s.passengerType, s.queueType), s.paxCount)
          }
        }
      } else {
        splits.find { case Splits(_, _, _, t) => t == Percentage } match {
          case None => Set()
          case Some(apiSplits) => apiSplits.splits.map {
            s => (PaxTypeAndQueue(s.passengerType, s.queueType), s.paxCount / 100 * bestFlightPax(flight))
          }
        }
      }
  }

  def aggregateSplits(bestFlightPax: Arrival => Int)(flights: Seq[ApiFlightWithSplits]): Map[PaxTypeAndQueue, Int] = {
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

  def flightsAtTerminal(flightsPcp: Seq[ApiFlightWithSplits], ourTerminal: Terminal): Seq[ApiFlightWithSplits] = {
    flightsPcp.filter(f => f.apiFlight.Terminal == ourTerminal)
  }

  def sumActPax(flights: Seq[ApiFlightWithSplits]): Int = flights.flatMap(_.apiFlight.ActPax).sum

  def sumBestPax(bestFlightSplitPax: ApiFlightWithSplits => Double)(flights: Seq[ApiFlightWithSplits]): Double = flights.map(bestFlightSplitPax).sum

  case class Props(flightCount: Int, actPaxCount: Int, bestPaxCount: Int, aggSplits: Map[PaxTypeAndQueue, Int], paxQueueOrder: Seq[PaxTypeAndQueue])

  def GraphComponent(splitTotal: Int, queuePax: Map[PaxTypeAndQueue, Int], paxQueueOrder: Seq[PaxTypeAndQueue]): TagOf[HTMLElement] = {
    val value = Try {
      val orderedSplitCounts: Seq[(PaxTypeAndQueue, Int)] = paxQueueOrder.map(ptq => ptq -> queuePax.getOrElse(ptq, 0))
      SplitsGraph.splitsGraphComponentColoured(SplitsGraph.Props(splitTotal, orderedSplitCounts))
    }
    val g: Try[TagOf[HTMLElement]] = value recoverWith {
      case f => Try(<.div(f.toString()))
    }
    g.get
  }
}
