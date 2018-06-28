package drt.server.feeds.test

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorSystem, Cancellable, Props}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.chroma.DiffingStage
import drt.shared.Arrival
import org.slf4j.{Logger, LoggerFactory}
import test.TestActors.ResetActor

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps

object TestFixtureFeed {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(actorSystem: ActorSystem): Source[Seq[Arrival], Cancellable] = {

    log.info(s"About to create test Arrival")
    val askableTestArrivalActor: AskableActorRef = actorSystem.actorOf(Props(classOf[TestArrivalsActor]), s"TestActor-LiveArrivals")

    implicit val timeout: Timeout = Timeout(300 milliseconds)

    val pollFrequency = 30 seconds
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val tickingSource: Source[List[Arrival], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed).map(x => {
      val testArrivals = Await.result(askableTestArrivalActor.ask(GetArrivals).map {
        case TestArrivals(arrivals) =>
          log.info(s"Got these arrivals from the actor: $arrivals")
          arrivals
        case x =>
          log.info(s"TEST: found this instead $x")

          List()
      }, 10 seconds)

      log.info(s"TEST: Sending arrivals from test feed: $testArrivals")

      testArrivals
    })

    tickingSource//.via(DiffingStage.DiffLists[Arrival]())
  }
}

case object GetArrivals

case class TestArrivals(arrivals: List[Arrival])

class TestArrivalsActor extends Actor with ActorLogging{

  var testArrivals: List[Arrival] = List[Arrival]()

  override def receive = {
    case a: Arrival =>
      log.info(s"TEST: Appending test arrival $a")

      testArrivals = testArrivals :+ a
      log.info(s"TEST: Arrivals now equal $testArrivals")

    case GetArrivals =>

      sender ! TestArrivals(testArrivals)
    case ResetActor =>
      testArrivals = List()
  }
}
