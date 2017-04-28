package services

import actors.ShiftsActor
import akka.actor.{ActorRef, Props}
import akka.pattern.AskableActorRef
import org.slf4j.LoggerFactory
import services.workloadcalculator.PaxLoadCalculator
import drt.shared.FlightsApi._
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared._
import services.workloadcalculator.PaxLoadCalculator.{MillisSinceEpoch, ProcTime}

import scala.collection.immutable._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}


trait FlightsService extends FlightsApi {
  def getFlights(st: Long, end: Long): Future[List[ApiFlight]]
  def getFlightsWithSplits(st: Long, end: Long): Future[FlightsWithSplits]

  def flights(startTimeEpoch: Long, endTimeEpoch: Long): Flights = {
    val fsFuture = getFlights(startTimeEpoch, endTimeEpoch)
    Flights(Await.result(fsFuture, Duration.Inf))
  }

  def flightsWithSplits(startTimeEpoch: Long, endTimeEpoch: Long): Future[FlightsWithSplits] = {
    val fsFuture: Future[FlightsWithSplits] = getFlightsWithSplits(startTimeEpoch, endTimeEpoch)
    fsFuture
  }
}

trait WorkloadsCalculator {
  private val log = LoggerFactory.getLogger(getClass)

  def splitRatioProvider: (ApiFlight) => Option[SplitRatios]

  def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double

  def queueLoadsByTerminal[L](flights: Future[List[ApiFlight]], queueLoadCalculator: ((ApiFlight) => IndexedSeq[(MillisSinceEpoch, PaxLoadCalculator.PaxTypeAndQueueCount)], (PaxTypeAndQueue) => ProcTime) => (List[ApiFlight]) => Map[QueueName, L]): Future[TerminalQueuePaxAndWorkLoads[L]] = {
    val flightsByTerminalFut: Future[Map[TerminalName, List[ApiFlight]]] = flights.map(fs => {
      val flightsByTerminal = fs.filterNot(freightOrEngineering).groupBy(_.Terminal)
      flightsByTerminal
    })

    val calcPaxTypeAndQueueCountForAFlightOverTime = PaxLoadCalculator.voyagePaxSplitsFlowOverTime(splitRatioProvider, (flight: ApiFlight) => SDate.parseString(flight.SchDT).millisSinceEpoch)_

    val workloadByTerminal: Future[Map[TerminalName, Map[QueueName, L]]] = flightsByTerminalFut.map((flightsByTerminal: Map[TerminalName, List[ApiFlight]]) =>
      flightsByTerminal.map((fbt: (TerminalName, List[ApiFlight])) => {
        log.debug(s"Got flights by terminal ${fbt}")
        val terminalName = fbt._1
        val flights = fbt._2
        val plc = queueLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime, procTimesProvider(terminalName))
        (terminalName -> plc(flights))
      }))

    workloadByTerminal
  }

  def freightOrEngineering(flight: ApiFlight): Boolean = Set("FRT", "ENG").contains(flight.Terminal)
}

