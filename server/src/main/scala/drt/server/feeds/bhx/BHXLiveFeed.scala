package drt.server.feeds.bhx

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate

import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object BHXLiveFeed extends BHXFeedConfig {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(endPointUrl: String, pollFrequency: FiniteDuration)(implicit actorSystem: ActorSystem): Source[ArrivalsFeedResponse, Cancellable] = {
    val feed = BHXFeed(serviceSoap(endPointUrl))
    val tickingSource: Source[ArrivalsFeedResponse, Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map(_ => {
        Try {
          log.info(s"About to get BHX live arrivals.")
          feed.getLiveArrivals
        } match {
          case Success(arrivals) =>
            log.info(s"Got ${arrivals.size} BHX live arrivals.")
            ArrivalsFeedSuccess(Flights(arrivals), SDate.now())
          case Failure(t) =>
            log.info(s"Failed to fetch BHX live arrivals.", t)
            ArrivalsFeedFailure(t.toString, SDate.now())
        }
      })

    tickingSource
  }
}
