package drt.server.feeds.chroma

import akka.actor.Cancellable
import akka.stream.scaladsl.Source
import drt.chroma.StreamingChromaFlow
import drt.chroma.chromafetcher.ChromaFetcher
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaForecastFlight, ChromaLiveFlight}
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

case class ChromaLiveFeed(chromaFetcher: ChromaFetcher[ChromaLiveFlight]) {
  val log: Logger = LoggerFactory.getLogger(getClass)

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

  def chromaEdiFlights()(implicit ec: ExecutionContext): Source[ArrivalsFeedResponse, Cancellable] = {
    val chromaFlow = StreamingChromaFlow.chromaPollingSource(chromaFetcher, 30 seconds, StreamingChromaFlow.liveChromaToArrival)

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

  def chromaVanillaFlights(frequency: FiniteDuration)(implicit ec: ExecutionContext): Source[ArrivalsFeedResponse, Cancellable] = {
    StreamingChromaFlow.chromaPollingSource(chromaFetcher, frequency, StreamingChromaFlow.liveChromaToArrival)
  }
}

case class ChromaForecastFeed(chromaFetcher: ChromaFetcher[ChromaForecastFlight]) {
  flightFeed =>

  def chromaVanillaFlights(frequency: FiniteDuration)(implicit ec: ExecutionContext): Source[ArrivalsFeedResponse, Cancellable] = {
    StreamingChromaFlow.chromaPollingSource(chromaFetcher, frequency, StreamingChromaFlow.forecastChromaToArrival)
  }
}
