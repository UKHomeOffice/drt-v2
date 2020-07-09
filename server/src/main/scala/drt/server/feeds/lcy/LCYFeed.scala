package drt.server.feeds.lcy

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object LCYFeed {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(client: LcyClientSupport, pollFrequency: FiniteDuration, initialDelay: FiniteDuration)(implicit actorSystem: ActorSystem): Source[ArrivalsFeedResponse, Cancellable] = {
    var initialRequest = true
    val tickingSource: Source[ArrivalsFeedResponse, Cancellable] = Source.tick(initialDelay, pollFrequency, NotUsed)
      .mapAsync(1)(_ => {
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

    tickingSource
  }

}