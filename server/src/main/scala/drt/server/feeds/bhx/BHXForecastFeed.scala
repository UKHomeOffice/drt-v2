package drt.server.feeds.bhx

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import drt.shared.Arrival
import org.slf4j.{Logger, LoggerFactory}
import scala.collection.immutable.Seq
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object BHXForecastFeed extends BHXFeedConfig {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(endPointUrl: String)(implicit actorSystem: ActorSystem): Source[Seq[Arrival], Cancellable] = {

      val feed = BHXFeed(serviceSoap(endPointUrl))
      val tickingSource: Source[List[Arrival], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map(_ => {
        Try {
          log.info(s"About to get BHX forecast arrivals.")
          feed.getForecastArrivals
        } match {
          case Success(arrivals) =>
            log.info(s"Got ${arrivals.size} BHX forecast arrivals.")
            arrivals
          case Failure(t) =>
            log.info(s"Failed to fetch BHX forecast arrivals.", t)
            List.empty[Arrival]
        }
      })

    tickingSource
  }
}
