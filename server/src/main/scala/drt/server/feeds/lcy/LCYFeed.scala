package drt.server.feeds.lcy

import org.apache.pekko.actor.{ActorSystem, typed}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import drt.server.feeds.Feed.FeedTick
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global

object LCYFeed {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(client: LcyClientSupport, source: Source[FeedTick, typed.ActorRef[FeedTick]])
           (implicit actorSystem: ActorSystem, materializer: Materializer): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] = {
    var initialRequest = true
    source
      .mapAsync(1) { _ =>
        log.info(s"Requesting LCY Feed")
        if (initialRequest) {
          client.initialFlights.map {
            case s: ArrivalsFeedSuccess =>
              initialRequest = false
              s
            case f: ArrivalsFeedFailure => f
          }
        } else {
          client.updateFlights
        }
      }
  }
}
