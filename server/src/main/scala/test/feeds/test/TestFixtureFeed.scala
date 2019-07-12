package test.feeds.test

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorSystem, Cancellable, Props}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate
import test.TestActors.ResetActor

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.Try

object TestFixtureFeed {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(actorSystem: ActorSystem): Source[ArrivalsFeedResponse, Cancellable] = {

    log.info(s"About to create test Arrival")
    val askableTestArrivalActor: AskableActorRef = actorSystem.actorOf(Props(classOf[TestArrivalsActor]), s"TestActor-LiveArrivals")

    implicit val timeout: Timeout = Timeout(1 seconds)

    val pollFrequency = 2 seconds
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val tickingSource: Source[ArrivalsFeedResponse, Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed).throttle(1, 2 seconds).map(_ => {
      val eventualArrivals = askableTestArrivalActor.ask(GetArrivals).map {
        case Arrivals(arrivals) =>
          log.debug(s"Got these arrivals from the actor: $arrivals")
          arrivals
        case x =>
          log.warn(s"TEST arrivals feed: found this instead $x")

          List()
      }.recoverWith {
        case t =>
          log.error(s"TEST arrivals feed: threw", t)

          Future(List())
      }

      val testArrivals = Try(Await.result(eventualArrivals, 1 seconds)).getOrElse(List())

      log.debug(s"TEST: Sending arrivals from test feed: $testArrivals")

      ArrivalsFeedSuccess(Flights(testArrivals), SDate.now())
    })

    tickingSource
  }
}

case object GetArrivals

case class Arrivals(arrivals: List[Arrival])

class TestArrivalsActor extends Actor with ActorLogging{

  var testArrivals: List[Arrival] = List[Arrival]()

  override def receive: PartialFunction[Any, Unit] = {
    case a: Arrival =>
      log.info(s"TEST: Appending test arrival $a")

      testArrivals = testArrivals :+ a
      log.info(s"TEST: Arrivals now equal $testArrivals")

    case GetArrivals =>
      sender ! Arrivals(testArrivals)

    case ResetActor =>
      testArrivals = List()
  }
}
