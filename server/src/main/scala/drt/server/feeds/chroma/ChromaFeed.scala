package drt.server.feeds.chroma

import akka.actor.Cancellable
import akka.event.LoggingAdapter
import akka.stream.scaladsl.Source
import drt.chroma.StreamingChromaFlow
import drt.chroma.chromafetcher.{ChromaFetcher, ChromaFetcherForecast}
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}

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

    def ediBaggageTerminalHack(csf: Arrival): Arrival = {
      if (csf.BaggageReclaimId.getOrElse("") == "7") csf.copy(Terminal = ArrivalsHall2) else csf
    }
  }

  def chromaEdiFlights(): Source[ArrivalsFeedResponse, Cancellable] = {
    val chromaFlow = StreamingChromaFlow.chromaPollingSourceLive(log, chromaFetcher, 30 seconds)

    chromaFlow.map {
      case aff: ArrivalsFeedFailure => aff
      case afs: ArrivalsFeedSuccess => afs.copy(arrivals = Flights(correctEdiTerminals(afs)))
    }
  }

  def correctEdiTerminals(afs: ArrivalsFeedSuccess): Seq[Arrival] = afs.arrivals.flights
    .map(EdiChroma.ediBaggageTerminalHack(_))
    .map(csf => EdiChroma.ediMapTerminals.get(csf.Terminal) match {
      case Some(renamedTerminal) => csf.copy(Terminal = renamedTerminal)
      case None => csf
    })

  def chromaVanillaFlights(frequency: FiniteDuration): Source[ArrivalsFeedResponse, Cancellable] = {
    StreamingChromaFlow.chromaPollingSourceLive(log, chromaFetcher, frequency)
  }
}

case class ChromaForecastFeed(log: LoggingAdapter, chromaFetcher: ChromaFetcherForecast) {
  flightFeed =>

  def chromaVanillaFlights(frequency: FiniteDuration): Source[ArrivalsFeedResponse, Cancellable] = {
    StreamingChromaFlow.chromaPollingSourceForecast(log, chromaFetcher, frequency)
  }
}
