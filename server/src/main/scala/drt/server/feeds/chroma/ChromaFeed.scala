package drt.server.feeds.chroma

import akka.actor.Cancellable
import akka.stream.scaladsl.Source
import drt.chroma.StreamingChromaFlow
import drt.chroma.chromafetcher.ChromaFetcher
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaForecastFlight, ChromaLiveFlight}
import drt.shared.FlightsApi.Flights
import drt.shared.Terminals._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

case class ChromaLiveFeed(chromaFetcher: ChromaFetcher[ChromaLiveFlight]) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def chromaEdiFlights()(implicit ec: ExecutionContext): Source[ArrivalsFeedResponse, Cancellable] = {
    val chromaFlow = StreamingChromaFlow.chromaPollingSource(
      chromaFetcher,
      30 seconds,
      StreamingChromaFlow.liveChromaToArrival
    )

    chromaFlow.map {
      case aff: ArrivalsFeedFailure => aff
      case afs: ArrivalsFeedSuccess => afs.copy(arrivals = Flights(correctEdiTerminals(afs)))
    }
  }

  def correctEdiTerminals(afs: ArrivalsFeedSuccess): Iterable[Arrival] = afs.arrivals.flights
    .map(csf => csf.copy(Terminal = A1))

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
