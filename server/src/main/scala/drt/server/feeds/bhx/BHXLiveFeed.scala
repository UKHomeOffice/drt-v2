package drt.server.feeds.bhx

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import com.typesafe.config.ConfigFactory
import drt.shared.Arrival
import org.slf4j.{Logger, LoggerFactory}
import scala.collection.immutable.Seq
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object BHXLiveFeed extends BHXFeedConfig {
  override val log: Logger = LoggerFactory.getLogger(getClass)
  override val endPointUrl: String = ConfigFactory.load.getString("feeds.bhx.soap.endPointUrl")

  def apply()(implicit actorSystem: ActorSystem): Source[Seq[Arrival], Cancellable] = {

      val tickingSource: Source[List[Arrival], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map(_ => {
        Try {
          log.info(s"About to get BHX live arrivals.")
          feed.getLiveArrivals
        } match {
          case Success(arrivals) =>
            log.info(s"Got ${arrivals.size} BHX live arrivals.")
            arrivals
          case Failure(t) =>
            log.info(s"Failed to fetch BHX live arrivals.", t)
            List.empty[Arrival]
        }
      })

    tickingSource
  }
}
