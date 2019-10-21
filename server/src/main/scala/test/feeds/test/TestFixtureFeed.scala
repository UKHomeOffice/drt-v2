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

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps

object TestFixtureFeed {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(actorSystem: ActorSystem): Source[ArrivalsFeedResponse, Cancellable] = {

    log.info(s"About to create test Arrival")
    val askableTestArrivalActor: AskableActorRef = actorSystem.actorOf(Props(classOf[TestArrivalsActor]), s"TestActor-LiveArrivals")

    implicit val timeout: Timeout = Timeout(1 seconds)

    val pollFrequency = 2 seconds
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val tickingSource = Source
      .tick(initialDelayImmediately, pollFrequency, NotUsed)
      .mapAsync(1) { _ =>
        askableTestArrivalActor
          .ask(GetArrivals)
          .map { case Arrivals(arrivals) => arrivals }
          .recover { case _ => List() }
      }
      .collect {
        case arrivals if arrivals.nonEmpty => ArrivalsFeedSuccess(Flights(arrivals), SDate.now())
      }

    tickingSource
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

      log.info(s"TEST: Arrivals now equal $testArrivals")

    case GetArrivals =>
      val toSend = Arrivals(testArrivals.getOrElse(List()))
      if (toSend.arrivals.nonEmpty) log.info(s"Sending test arrivals: ${toSend.arrivals}")
      sender() ! toSend
      testArrivals = None

    case ResetActor =>
      testArrivals = None
  }
}
