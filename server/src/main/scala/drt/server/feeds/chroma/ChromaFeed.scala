package drt.server.feeds.chroma

import akka.actor.Cancellable
import akka.event.LoggingAdapter
import akka.stream.scaladsl.Source
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaForecastFlight, ChromaLiveFlight}
import drt.chroma.chromafetcher.{ChromaFetcherForecast, ChromaFetcher}
import drt.chroma.{DiffingStage, StreamingChromaFlow}
import drt.shared.Arrival
import services.SDate

import scala.concurrent.duration._
import scala.language.postfixOps

case class ChromaLiveFeed(log: LoggingAdapter, chromaFetcher: ChromaFetcher) {
  flightFeed =>

  object EdiChroma {
    val ArrivalsHall1 = "A1"
    val ArrivalsHall2 = "A2"
    val ediMapTerminals = Map(
      "T1" -> ArrivalsHall1,
      "T2" -> ArrivalsHall2
    )

    def ediBaggageTerminalHack(csf: ChromaLiveFlight): ChromaLiveFlight = {
      if (csf.BaggageReclaimId == "7") csf.copy(Terminal = ArrivalsHall2) else csf
    }
  }

  def apiFlightCopy(ediMapping: Source[Seq[ChromaLiveFlight], Cancellable]): Source[List[Arrival], Cancellable] = {
    ediMapping.map(flights =>
      flights.map(flight => {
        val walkTimeMinutes = 4
        val pcpTime: Long = org.joda.time.DateTime.parse(flight.SchDT).plusMinutes(walkTimeMinutes).getMillis
        Arrival(
          Operator = flight.Operator,
          Status = flight.Status, EstDT = flight.EstDT,
          ActDT = flight.ActDT, EstChoxDT = flight.EstChoxDT,
          ActChoxDT = flight.ActChoxDT,
          Gate = flight.Gate,
          Stand = flight.Stand,
          MaxPax = flight.MaxPax,
          ActPax = flight.ActPax,
          TranPax = flight.TranPax,
          RunwayID = flight.RunwayID,
          BaggageReclaimId = flight.BaggageReclaimId,
          FlightID = flight.FlightID,
          AirportID = flight.AirportID,
          Terminal = flight.Terminal,
          rawICAO = flight.ICAO,
          rawIATA = flight.IATA,
          Origin = flight.Origin,
          SchDT = flight.SchDT,
          PcpTime = pcpTime,
          Scheduled = SDate(flight.SchDT).millisSinceEpoch
        )
      }).toList)
  }

  def chromaEdiFlights(): Source[List[Arrival], Cancellable] = {
    val chromaFlow = StreamingChromaFlow.chromaPollingSourceLive(log, chromaFetcher, 30 seconds)

    def ediMapping = chromaFlow.via(DiffingStage.DiffLists[ChromaLiveFlight]()).map(csfs =>
      csfs.map(EdiChroma.ediBaggageTerminalHack(_)).map(csf => EdiChroma.ediMapTerminals.get(csf.Terminal) match {
        case Some(renamedTerminal) =>
          csf.copy(Terminal = renamedTerminal)
        case None => csf
      })
    )

    apiFlightCopy(ediMapping)
  }

  def chromaVanillaFlights(frequency: FiniteDuration): Source[List[Arrival], Cancellable] = {
    val chromaFlow = StreamingChromaFlow.chromaPollingSourceLive(log, chromaFetcher, frequency)
    apiFlightCopy(chromaFlow.via(DiffingStage.DiffLists[ChromaLiveFlight]()))
  }
}

case class ChromaForecastFeed(log: LoggingAdapter, chromaFetcher: ChromaFetcherForecast) {
  flightFeed =>

  def apiFlightCopy(ediMapping: Source[Seq[ChromaForecastFlight], Cancellable]): Source[List[Arrival], Cancellable] = {
    ediMapping.map(flights =>
      flights.map(flight => {
        val walkTimeMinutes = 4
        val pcpTime: Long = org.joda.time.DateTime.parse(flight.SchDT).plusMinutes(walkTimeMinutes).getMillis
        Arrival(
          Operator = "",
          Status = "Port Forecast",
          EstDT = "",
          ActDT = "",
          EstChoxDT = "",
          ActChoxDT = "",
          Gate = "",
          Stand = "",
          MaxPax = 0,
          ActPax = flight.EstPax,
          TranPax = flight.EstTranPax,
          RunwayID = "",
          BaggageReclaimId = "",
          FlightID = flight.FlightID,
          AirportID = flight.AirportID,
          Terminal = flight.Terminal,
          rawICAO = flight.ICAO,
          rawIATA = flight.IATA,
          Origin = flight.Origin,
          SchDT = flight.SchDT,
          PcpTime = pcpTime,
          Scheduled = SDate(flight.SchDT).millisSinceEpoch
        )
      }).toList)
  }

  def chromaVanillaFlights(frequency: FiniteDuration): Source[List[Arrival], Cancellable] = {
    val chromaFlow = StreamingChromaFlow.chromaPollingSourceForecast(log, chromaFetcher, frequency)
    apiFlightCopy(chromaFlow.via(DiffingStage.DiffLists[ChromaForecastFlight]()))
  }
}
