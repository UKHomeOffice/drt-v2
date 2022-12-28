package drt.server.feeds.legacy.bhx

import akka.actor.typed
import akka.stream.scaladsl.Source
import drt.server.feeds.Feed.FeedTick
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import uk.gov.homeoffice.drt.time.SDate

import scala.util.{Failure, Success, Try}

object BHXForecastFeedLegacy extends BHXFeedConfig {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(endPointUrl: String, source: Source[FeedTick, typed.ActorRef[FeedTick]]): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] = {
    val feed = BHXFeed(serviceSoap(endPointUrl))
    source.map(_ => {
      Try {
        log.info(s"About to get BHX forecast arrivals.")
        feed.getForecastArrivals
      } match {
        case Success(arrivals) =>
          log.info(s"Got ${arrivals.size} BHX forecast arrivals.")
          ArrivalsFeedSuccess(Flights(arrivals), SDate.now())
        case Failure(t) =>
          log.info(s"Failed to fetch BHX forecast arrivals.", t)
          ArrivalsFeedFailure(t.toString, SDate.now())
      }
    })
  }
}
