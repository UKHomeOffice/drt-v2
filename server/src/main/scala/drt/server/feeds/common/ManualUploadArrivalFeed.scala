package drt.server.feeds.common

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess, GetFeedImportArrivals}
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.FeedArrival
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class ManualUploadArrivalFeed(arrivalsActor: ActorRef)(implicit timeout: Timeout) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def requestFeed: Future[ArrivalsFeedResponse] =
    arrivalsActor.ask(GetFeedImportArrivals)
      .mapTo[Option[Seq[FeedArrival]]]
      .map {
        case Some(arrivals) =>
          log.info(s"Got ${arrivals.size} port arrivals")
          ArrivalsFeedSuccess(arrivals, SDate.now())
        case _ =>
          ArrivalsFeedSuccess(Seq(), SDate.now())
      }
      .recoverWith {
        case e => Future(ArrivalsFeedFailure(e.getMessage, SDate.now()))
      }
}
