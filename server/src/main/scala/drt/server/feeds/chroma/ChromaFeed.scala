package drt.server.feeds.chroma

import akka.actor.Cancellable
import akka.event.LoggingAdapter
import akka.stream.scaladsl.Source
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaForecastFlight, ChromaLiveFlight}
import drt.chroma.chromafetcher.{ChromaFetcher, ChromaFetcherForecast}
import drt.chroma.{DiffingStage, StreamingChromaFlow}
import drt.shared.Arrival
import org.springframework.util.StringUtils
import services.SDate

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

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
        val est = Try(SDate(flight.EstDT).millisSinceEpoch).getOrElse(0L)
        val act = Try(SDate(flight.ActDT).millisSinceEpoch).getOrElse(0L)
        val estChox = Try(SDate(flight.EstChoxDT).millisSinceEpoch).getOrElse(0L)
        val actChox = Try(SDate(flight.ActChoxDT).millisSinceEpoch).getOrElse(0L)
        Arrival(
          Operator = if (StringUtils.isEmpty(flight.Operator)) None else Option(flight.Operator),
          Status = flight.Status,
          Estimated = if (est == 0) None else Option(est),
          Actual = if (act == 0) None else Option(act),
          EstimatedChox = if (estChox == 0) None else Option(estChox),
          ActualChox = if (actChox == 0) None else Option(actChox),
          Gate = if (StringUtils.isEmpty(flight.Gate)) None else Option(flight.Gate),
          Stand = if (StringUtils.isEmpty(flight.Stand)) None else Option(flight.Stand),
          MaxPax = if (flight.MaxPax == 0) None else Option(flight.MaxPax),
          ActPax = if (flight.ActPax == 0) None else Option(flight.ActPax),
          TranPax = if (flight.ActPax == 0) None else Option(flight.TranPax),
          RunwayID = if (StringUtils.isEmpty(flight.RunwayID)) None else Option(flight.RunwayID),
          BaggageReclaimId = if (StringUtils.isEmpty(flight.BaggageReclaimId)) None else Option(flight.BaggageReclaimId),
          FlightID = if (flight.FlightID == 0) None else Option(flight.FlightID),
          AirportID = flight.AirportID,
          Terminal = flight.Terminal,
          rawICAO = flight.ICAO,
          rawIATA = flight.IATA,
          Origin = flight.Origin,
          PcpTime = Some(pcpTime),
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
          Operator = None,
          Status = "Port Forecast",
          Estimated = None,
          Actual = None,
          EstimatedChox = None,
          ActualChox = None,
          Gate = None,
          Stand = None,
          MaxPax = None,
          ActPax = if (flight.EstPax == 0) None else Option(flight.EstPax),
          TranPax = if (flight.EstPax == 0) None else Option(flight.EstTranPax),
          RunwayID = None,
          BaggageReclaimId = None,
          FlightID = if (flight.FlightID == 0) None else Option(flight.FlightID),
          AirportID = flight.AirportID,
          Terminal = flight.Terminal,
          rawICAO = flight.ICAO,
          rawIATA = flight.IATA,
          Origin = flight.Origin,
          PcpTime = Option(pcpTime),
          Scheduled = SDate(flight.SchDT).millisSinceEpoch
        )
      }).toList)
  }

  def chromaVanillaFlights(frequency: FiniteDuration): Source[List[Arrival], Cancellable] = {
    val chromaFlow = StreamingChromaFlow.chromaPollingSourceForecast(log, chromaFetcher, frequency)
    apiFlightCopy(chromaFlow.via(DiffingStage.DiffLists[ChromaForecastFlight]()))
  }
}
