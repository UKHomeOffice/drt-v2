package drt.server.feeds.chroma

import akka.actor.typed
import akka.stream.scaladsl.Source
import drt.chroma.StreamingChromaFlow
import drt.chroma.chromafetcher.ChromaFetcher
import drt.chroma.chromafetcher.ChromaFetcher.ChromaLiveFlight
import drt.server.feeds.ArrivalsFeedResponse
import drt.server.feeds.Feed.FeedTick
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext

case class ChromaLiveFeed(chromaFetcher: ChromaFetcher[ChromaLiveFlight]) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def chromaVanillaFlights(source: Source[FeedTick, typed.ActorRef[FeedTick]])
                          (implicit ec: ExecutionContext): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] = {
    StreamingChromaFlow.chromaPollingSource(chromaFetcher, StreamingChromaFlow.liveChromaToArrival, source)
  }
}
