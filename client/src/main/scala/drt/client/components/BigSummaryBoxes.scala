package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.arrivals.SplitStyle.{PaxNumbers, Percentage}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Splits}
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PaxTypeAndQueue, Queues}
import uk.gov.homeoffice.drt.time.SDateLike

case class DisplayPaxTypesAndQueues(displayLabel: String, paxTypeAndQueues: Seq[PaxTypeAndQueue]) {
  override def toString: String = s"$displayLabel -> $paxTypeAndQueues"
}

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

  def countPaxInPeriod(rootModel: RootModel, now: SDateLike, nowPlus3Hours: SDateLike, sourceOrderPreference: Seq[FeedSource]): Pot[Int] = {
    rootModel.portStatePot.map(portState => {
      val flights: Seq[ApiFlightWithSplits] = flightsInPeriod(portState.flights.values.toList, now, nowPlus3Hours)
      sumActPax(flights, sourceOrderPreference)
    })
  }

  def bestFlightSplits(paxFeedSourceOrder: List[FeedSource]): ApiFlightWithSplits => Set[(PaxTypeAndQueue, Double)] = {
    case ApiFlightWithSplits(_, s, _) if s.isEmpty => Set()
    case fws@ApiFlightWithSplits(_, splits, _) =>
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
            s => (PaxTypeAndQueue(s.passengerType, s.queueType), s.paxCount / 100 * fws.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder).getOrElse(0))
          }
        }
      }
  }

  def aggregateSplits(flights: Iterable[ApiFlightWithSplits], paxFeedSourceOrder: List[FeedSource]): Map[PaxTypeAndQueue, Int] = {
    val newSplits = Map[PaxTypeAndQueue, Double]()
    val getSplits = bestFlightSplits(paxFeedSourceOrder)
    val allSplits: Iterable[(PaxTypeAndQueue, Double)] = flights.flatMap(getSplits)
    val splitsExcludingTransfers = allSplits.filter(_._1.queueType != Queues.Transfer)
    val aggSplits: Map[PaxTypeAndQueue, Double] = splitsExcludingTransfers.foldLeft(newSplits) {
      case (agg, (k, v)) =>
        val g = agg.getOrElse(k, 0d)
        agg.updated(k, v + g)
    }
    val aggSplitsInts: Map[PaxTypeAndQueue, Int] = aggSplits.view.mapValues(Math.round(_).toInt).toMap

    aggSplitsInts
  }

  def flightsAtTerminal(flightsPcp: Seq[ApiFlightWithSplits], ourTerminal: Terminal): Seq[ApiFlightWithSplits] = {
    flightsPcp.filter(f => f.apiFlight.Terminal == ourTerminal)
  }

  def sumActPax(flights: Seq[ApiFlightWithSplits],
                sourceOrderPreference: Seq[FeedSource]): Int = flights.map(_.apiFlight.bestPcpPaxEstimate(sourceOrderPreference).getOrElse(0)).sum

  case class Props(flightCount: Int,
                   actPaxCount: Int,
                   bestPaxCount: Int,
                   aggSplits: Map[PaxTypeAndQueue, Int],
                   paxQueueOrder: Seq[PaxTypeAndQueue]) extends UseValueEq

  private val splitPassengerQueueMapping = Seq(
    DisplayPaxTypesAndQueues("to eGates", Seq(gbrNationalToEgate, eeaMachineReadableToEGate, b5jsskToEGate)),
    DisplayPaxTypesAndQueues("B5J+ to EEA", Seq(b5jsskToDesk, b5jsskChildToDesk)),
    DisplayPaxTypesAndQueues("EEA to EEA", Seq(eeaMachineReadableToDesk, eeaNonMachineReadableToDesk, eeaChildToDesk)),
    DisplayPaxTypesAndQueues("GBR to EEA", Seq(gbrNationalToDesk, gbrNationalChildToDesk)),
    DisplayPaxTypesAndQueues("Non-Visa to Non-EEA", Seq(nonVisaNationalToDesk)),
    DisplayPaxTypesAndQueues("Visa to Non-EEA", Seq(visaNationalToDesk)),
  )

  private val splitPassengerQueueOtherPorts = Seq(
    DisplayPaxTypesAndQueues("to eGates" , Seq(gbrNationalToEgate, eeaMachineReadableToEGate, b5jsskToEGate)),
    DisplayPaxTypesAndQueues("B5J+ to Desk" , Seq(b5jsskToDesk, b5jsskChildToDesk)),
    DisplayPaxTypesAndQueues("EEA to Desk" , Seq(eeaMachineReadableToDesk, eeaNonMachineReadableToDesk, eeaChildToDesk)),
    DisplayPaxTypesAndQueues("GBR to Desk" , Seq(gbrNationalToDesk, gbrNationalChildToDesk)),
    DisplayPaxTypesAndQueues("Non-Visa to Desk" , Seq(nonVisaNationalToDesk)),
    DisplayPaxTypesAndQueues("Visa to Desk" , Seq(visaNationalToDesk)),
  )

  private def paxSplitPercentages(queuePax: Map[PaxTypeAndQueue, Int], mapping: Seq[DisplayPaxTypesAndQueues]): Seq[(String, Int)] = {
    val totalPaxCount = queuePax.values.sum
    mapping.map {
      displayPaxTypesAndQueues =>
        val queuePaxCount = displayPaxTypesAndQueues.paxTypeAndQueues.map(ptq => queuePax.getOrElse(ptq, 0)).sum.toDouble
        val paxCountPercentage = ((queuePaxCount / totalPaxCount) * 100).toInt
        (displayPaxTypesAndQueues.displayLabel, paxCountPercentage)
    }
  }

  def paxSplitPercentagesWithSingleDeskQueue(queuePax: Map[PaxTypeAndQueue, Int]): Seq[(String, Int)] = {
    paxSplitPercentages(queuePax, splitPassengerQueueOtherPorts)
  }

  def paxSplitPercentagesWithSplitDeskQueues(queuePax: Map[PaxTypeAndQueue, Int]): Seq[(String, Int)] = {
    paxSplitPercentages(queuePax, splitPassengerQueueMapping)
  }

}
