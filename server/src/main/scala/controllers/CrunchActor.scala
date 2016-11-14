package controllers

import java.nio.ByteBuffer

import akka.actor._
import akka.pattern.AskableActorRef
import akka.stream.Materializer
import akka.stream.actor.ActorSubscriberMessage.OnComplete
import akka.stream.scaladsl.{Source, Sink}
import akka.util.Timeout
import boopickle.Default._
import com.google.inject.Inject
import com.typesafe.config.{ConfigFactory, Config}
import drt.chroma.{DiffingStage, StreamingChromaFlow}
import drt.chroma.chromafetcher.ChromaFetcher
import drt.chroma.chromafetcher.ChromaFetcher.ChromaSingleFlight
import drt.chroma.rabbit.JsonRabbit
import http.{WithSendAndReceive, ProdSendAndReceive}
import play.api.{Configuration, Environment}
import play.api.mvc._
import services.{CrunchCalculator, WorkloadsCalculator, ApiService}
import spatutorial.shared.FlightsApi._
import spatutorial.shared.{ApiFlight, Api}
import spray.http._
import scala.language.postfixOps
import scala.util.{Failure, Try, Success}

//import scala.collection.immutable.Seq
import scala.collection.{immutable, mutable}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import spatutorial.shared._
import scala.concurrent.ExecutionContext.Implicits.global

//i'm of two minds about the benefit of having this message independent of the Flights() message.
case class CrunchFlightsChange(flights: Seq[ApiFlight])

case class GetLatestCrunch()


class CrunchActor(crunchPeriodHours: Int) extends Actor with ActorLogging with WorkloadsCalculator with CrunchCalculator with AirportConfig {
  var terminalQueueLatestCrunch: Map[TerminalName, Map[QueueName, CrunchResult]] = Map()
  var flights: List[ApiFlight] = Nil
  var latestWorkloads: Option[TerminalQueueWorkloads] = None

  //todo put some spray caching here, I think.
  var latestCrunch: Future[CrunchResult] = Future.failed(new Exception("No Crunch Available"))

  def receive = {
    case CrunchFlightsChange(newFlights) =>
      flights = newFlights.toList
      latestCrunch = performCrunch
    case GetLatestCrunch() =>
      log.info("Received GetLatestCrunch()")
      log.info(s"And our flights are ${flights}")
      val replyTo = sender()
      flights match {
        case Nil =>
          replyTo ! NoCrunchAvailable()
        case fs =>
          for (cr <- latestCrunch) {
            log.info(s"latestCrunch available, will send $cr")
            replyTo ! cr
          }
      }
    case message =>
      log.info(s"crunchActor received ${message}")
  }


  def performCrunch: Future[CrunchResult] = {
    log.info("Performing a crunch")
    val workloads: Future[TerminalQueueWorkloads] = getWorkloadsByTerminal(Future(flights))
    log.info(s"Workloads are ${workloads}")
    val terminalName: TerminalName = "A1"
    val queueName = "eeaDesk"
    val tq: QueueName = terminalName + "/" + queueName
    for (wl <- workloads) yield {
      log.info(s"in crunch, workloads have calced $wl")
      val triedWl: Try[Map[String, List[Double]]] = Try {
        val terminalWorkloads = wl(terminalName)
        val workloadsByQueue = WorkloadsHelpers.workloadsByQueue(terminalWorkloads, 1)
        log.info("looked up " + tq + " and got " + workloadsByQueue)
        workloadsByQueue
      }

      val r: Try[CrunchResult] = triedWl.flatMap {
        terminalWorkloads =>
          log.info(s"Will crunch now $tq")
          val workloads: List[Double] = terminalWorkloads(queueName)
          log.info(s"$tq Crunching on $workloads")
          val crunchRes: Try[CrunchResult] = tryCrunch(terminalName, queueName, workloads)
          log.info(s"Crunch complete for $tq $crunchRes")
          crunchRes
      }
      val asFutre = r match {
        case Success(s) =>
          log.info(s"Successful crunch for $tq")
          s
        case Failure(f) =>
          log.error(f, s"Failed to crunch $tq")
          throw f
      }
      asFutre
    }
  }
}
