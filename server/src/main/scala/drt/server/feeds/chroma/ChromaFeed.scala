package drt.server.feeds.chroma

import actors.Feed.FeedTick
import akka.actor.typed
import akka.stream.scaladsl.Source
import drt.chroma.StreamingChromaFlow
import drt.chroma.chromafetcher.ChromaFetcher
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaForecastFlight, ChromaLiveFlight}
import drt.shared.FlightsApi.Flights
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import uk.gov.homeoffice.drt.ports.Terminals._

import scala.concurrent.ExecutionContext

case class ChromaLiveFeed(chromaFetcher: ChromaFetcher[ChromaLiveFlight]) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def correctEdiTerminals(afs: ArrivalsFeedSuccess): Iterable[Arrival] = afs.arrivals.flights
    .map(csf => csf.copy(Terminal = A1))

  def chromaVanillaFlights(source: Source[FeedTick, typed.ActorRef[FeedTick]])
                          (implicit ec: ExecutionContext): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] = {
    StreamingChromaFlow.chromaPollingSource(chromaFetcher, StreamingChromaFlow.liveChromaToArrival, source)
  }
}

case class ChromaForecastFeed(chromaFetcher: ChromaFetcher[ChromaForecastFlight]) {
  flightFeed =>

  def chromaVanillaFlights(source: Source[FeedTick, typed.ActorRef[FeedTick]])
                          (implicit ec: ExecutionContext): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] = {
    StreamingChromaFlow.chromaPollingSource(chromaFetcher, StreamingChromaFlow.forecastChromaToArrival, source)
  }
}
