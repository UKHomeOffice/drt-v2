package test.feeds.test

import actors.acking.AckingReceiver.Ack
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, typed}
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.server.feeds.Feed.FeedTick
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.ArrivalsFeedSuccess
import services.SDate
import test.TestActors.ResetData
import uk.gov.homeoffice.drt.arrivals.Arrival

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

object TestFixtureFeed {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(actorSystem: ActorSystem, testArrivalActor: ActorRef, source: Source[FeedTick, typed.ActorRef[FeedTick]]): Source[ArrivalsFeedSuccess, typed.ActorRef[FeedTick]] = {
    log.info(s"About to create test Arrival")

    implicit val timeout: Timeout = Timeout(1 seconds)

    source
      .mapAsync(1) { _ =>
        testArrivalActor
          .ask(GetArrivals)
          .map { case Arrivals(arrivals) => arrivals }
          .recover { case _ => List() }
      }
      .collect {
        case arrivals if arrivals.nonEmpty => ArrivalsFeedSuccess(Flights(arrivals), SDate.now())
      }
  }
}

case object GetArrivals

case class Arrivals(arrivals: List[Arrival])

class TestArrivalsActor extends Actor with ActorLogging {

  var testArrivals: Option[List[Arrival]] = None

  override def receive: PartialFunction[Any, Unit] = {
    case a: Arrival =>
      log.info(s"TEST: Appending test arrival $a")

      testArrivals = testArrivals match {
        case None => Option(List(a))
        case Some(existing) => Option(existing :+ a)
      }

      sender() ! Ack

    case GetArrivals =>
      val toSend = Arrivals(testArrivals.getOrElse(List()))
      if (toSend.arrivals.nonEmpty) log.info(s"Sending test arrivals: ${toSend.arrivals.size}")
      sender() ! toSend
      testArrivals = None

    case ResetData =>
      testArrivals = None
      sender() ! Ack
  }
}
