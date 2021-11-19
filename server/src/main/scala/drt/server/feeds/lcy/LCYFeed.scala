package drt.server.feeds.lcy

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}

import scala.concurrent.ExecutionContext.Implicits.global

object LCYFeed {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(client: LcyClientSupport, source: Source[Nothing, ActorRef])
           (implicit actorSystem: ActorSystem, materializer: Materializer): Source[ArrivalsFeedResponse, ActorRef] = {
    var initialRequest = true
    source.mapAsync(1)(_ => {
      log.info(s"Requesting LCY Feed")
      if (initialRequest)
        client.initialFlights.map {
          case s: ArrivalsFeedSuccess =>
            initialRequest = false
            s
          case f: ArrivalsFeedFailure =>
            f
        }
      else
        client.updateFlights
    })
  }
}
