package drt.server.feeds.lcy

import actors.Feed.FeedTick
import akka.actor.{ActorSystem, typed}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}

import scala.concurrent.ExecutionContext.Implicits.global

object LCYFeed {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(client: LcyClientSupport, source: Source[FeedTick, typed.ActorRef[FeedTick]])
           (implicit actorSystem: ActorSystem, materializer: Materializer): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] = {
    var initialRequest = true
    println(s"hello here")
    source
      .map { _ =>
        println(s"hmm")
        "yeah"
      }
      .mapAsync(1) { _ =>
        log.info(s"Requesting LCY Feed")
        println(s"hello here2")
        if (initialRequest)
          client.initialFlights.map {
            case s: ArrivalsFeedSuccess =>
              println(s"hello here 2a")
              initialRequest = false
              s
            case f: ArrivalsFeedFailure =>
              println(s"hello here 2b")
              f
          }
        else
          client.updateFlights
      }
  }
}
