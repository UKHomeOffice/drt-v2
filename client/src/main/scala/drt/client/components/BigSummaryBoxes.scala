package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.components.FlightComponents._
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel
import drt.shared.CrunchApi.MillisSinceEpoch
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import org.scalajs.dom.raw.HTMLElement
import uk.gov.homeoffice.drt.arrivals.SplitStyle.{PaxNumbers, Percentage}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Splits}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{PaxTypeAndQueue, Queues}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.util.Try

object BigSummaryBoxes {
  def flightPcpInPeriod(f: ApiFlightWithSplits, start: SDateLike, end: SDateLike): Boolean = {
    val bt: Long = bestTime(f)
    start.millisSinceEpoch <= bt && bt <= end.millisSinceEpoch
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

  val bestFlightSplits: ApiFlightWithSplits => Set[(PaxTypeAndQueue, Double)] = {
    case ApiFlightWithSplits(_, s, _) if s.isEmpty => Set()
    case fws@ApiFlightWithSplits(flight, splits, _) =>
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
            s => (PaxTypeAndQueue(s.passengerType, s.queueType), s.paxCount / 100 * fws.pcpPaxEstimate)
          }
        }
      }
  }

  def aggregateSplits(flights: Iterable[ApiFlightWithSplits]): Map[PaxTypeAndQueue, Int] = {
    val newSplits = Map[PaxTypeAndQueue, Double]()
    val allSplits: Iterable[(PaxTypeAndQueue, Double)] = flights.flatMap(bestFlightSplits)
    val splitsExcludingTransfers = allSplits.filter(_._1.queueType != Queues.Transfer)
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

  case class Props(flightCount: Int, actPaxCount: Int, bestPaxCount: Int, aggSplits: Map[PaxTypeAndQueue, Int], paxQueueOrder: Seq[PaxTypeAndQueue]) extends UseValueEq

  def GraphComponent(splitTotal: Int, queuePax: Map[PaxTypeAndQueue, Int], paxQueueOrder: Iterable[PaxTypeAndQueue]): TagOf[HTMLElement] = {
    val value = Try {
      val orderedSplitCounts: Iterable[(PaxTypeAndQueue, Int)] = paxQueueOrder.map(ptq => ptq -> queuePax.getOrElse(ptq, 0))
      SplitsGraph.splitsGraphComponentColoured(SplitsGraph.Props(splitTotal, orderedSplitCounts))
    }
    val g: Try[TagOf[HTMLElement]] = value recoverWith {
      case f => Try(<.div(f.toString()))
    }
    g.get
  }
}
