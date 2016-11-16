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
import spray.caching.{Cache, LruCache}
import spray.http._
import scala.language.postfixOps
import scala.util.{Failure, Try, Success}
import spray.util._

//import scala.collection.immutable.Seq
import scala.collection.{immutable, mutable}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import spatutorial.shared._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import akka.actor.ActorSystem
import spray.caching.{LruCache, Cache}
import spray.util._


//i'm of two minds about the benefit of having this message independent of the Flights() message.
case class CrunchFlightsChange(flights: Seq[ApiFlight])

case class GetLatestCrunch(terminalName: TerminalName, queueName: QueueName)


class CrunchActor(crunchPeriodHours: Int,
                  override val airportConfig: AirportConfig) extends Actor
  with ActorLogging
  with WorkloadsCalculator
  with CrunchCalculator
  with HasAirportConfig
  with FlightState {

  var terminalQueueLatestCrunch: Map[TerminalName, Map[QueueName, CrunchResult]] = Map()

  var latestCrunch: Future[CrunchResult] = Future.failed(new Exception("No Crunch Available"))
  val crunchCache: Cache[CrunchResult] = LruCache()

  def cacheCrunch[T](terminal: TerminalName, queue: QueueName): Future[CrunchResult] = {
    val key: String = cacheKey(terminal, queue)
    log.info(s"getting crunch for $key")
    crunchCache(key) {
      //todo un-future this mess
      val expensiveCrunchResult = Await.result(performCrunch(terminal, queue), 15 seconds)
      log.info(s"Cache will soon have ${expensiveCrunchResult}")
      expensiveCrunchResult
    }
  }

  def cacheKey[T](terminal: TerminalName, queue: QueueName): String = s"$terminal/$queue"

  def receive = {
    case CrunchFlightsChange(newFlights) =>
      onFlightUpdates(newFlights.toList)
      newFlights match {
        case Nil =>
          log.info("No crunch, no change")
        case _ =>
          reCrunchAllTerminalsAndQueues()
      }
    case GetLatestCrunch(terminalName, queueName) =>
      log.info(s"Received GetLatestCrunch($terminalName, $queueName)")
      val replyTo = sender()
      log.info(s"Sender is ${sender}")
      flights.values match {
        case Nil =>
          replyTo ! NoCrunchAvailable()
        case fs =>
          val futCrunch = cacheCrunch(terminalName, queueName)
          log.info(s"got keyed thingy ${futCrunch}")
          //todo this is NOT right
          futCrunch.value match {
            case Some(Success(cr)) =>
              replyTo ! cr
            case Some(Failure(f)) =>
              log.info(s"unsuccessful crunch here $terminalName/$queueName")
              replyTo ! NoCrunchAvailable()
            case None =>
              log.error(s"got nothing $terminalName/$queueName")
              replyTo ! NoCrunchAvailable()
          }
      }
    case message =>
      log.info(s"crunchActor received ${message}")
  }


  def reCrunchAllTerminalsAndQueues(): Unit = {
    for (tn <- airportConfig.terminalNames; qn <- airportConfig.queues) {
      crunchCache.remove(cacheKey(tn, qn))
      cacheCrunch(tn, qn)
    }
  }

  def performCrunch(terminalName: TerminalName, queueName: QueueName): Future[CrunchResult] = {
    log.info("Performing a crunch")
    val workloads: Future[TerminalQueueWorkloads] = getWorkloadsByTerminal(Future(flights.values.toList))
    log.info(s"Workloads are ${workloads}")
    val tq: QueueName = terminalName + "/" + queueName
    for (wl <- workloads) yield {
      log.info(s"in crunch of $tq, workloads have calced $wl")
      val triedWl: Try[Map[String, List[Double]]] = Try {
        log.info(s"$tq lookup wl ")
        val terminalWorkloads = wl.get(terminalName)
        terminalWorkloads match {
          case Some(twl) =>
            log.info(s"wl now $terminalWorkloads")
            val workloadsByQueue = WorkloadsHelpers.workloadsByQueue(twl, crunchPeriodHours)
            log.info("looked up " + tq + " and got " + workloadsByQueue)
            workloadsByQueue
          case None =>
            Map()
        }
      }

      val r: Try[CrunchResult] = triedWl.flatMap {
        terminalWorkloads =>
          log.info(s"Will crunch now $tq")
          val workloads: List[Double] = terminalWorkloads(queueName)
          log.info(s"$tq Crunching on $workloads")
          val queueSla = airportConfig.slaByQueue(queueName)
          val crunchRes: Try[CrunchResult] = tryCrunch(terminalName, queueName, workloads, queueSla)
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
