package drt.server.feeds.common

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess, GetFeedImportArrivals}
import services.SDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class ManualUploadArrivalFeed(arrivalsActor: ActorRef)(implicit timeout: Timeout) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def requestFeed: Future[ArrivalsFeedResponse] =
    arrivalsActor.ask(GetFeedImportArrivals)
      .map {
        case Some(Flights(arrivals)) =>
          log.info(s"Got ${arrivals.size} port arrivals")
          ArrivalsFeedSuccess(Flights(arrivals), SDate.now())
        case _ =>
          ArrivalsFeedSuccess(Flights(Seq()), SDate.now())
      }
      .recoverWith {
        case e => Future(ArrivalsFeedFailure(e.getMessage, SDate.now()))
      }
}
