package controllers

import java.util.Date

import akka.actor._
import org.joda.time.{DateTime, LocalDate}
import org.joda.time.format.DateTimeFormat
import services.{CrunchCalculator, OptimizerConfig, TryRenjin, WorkloadsCalculator}
import spatutorial.shared.ApiFlight
import spatutorial.shared.FlightsApi._

import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import spatutorial.shared._
import spray.caching.{Cache, LruCache}

import scala.collection.immutable.{NumericRange, Seq}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


//i'm of two minds about the benefit of having this message independent of the Flights() message.
case class PerformCrunchOnFlights(flights: Seq[ApiFlight])

case class GetLatestCrunch(terminalName: TerminalName, queueName: QueueName)

case class SaveCrunchResult(terminalName: TerminalName, queueName: QueueName, crunchResult: CrunchResultWithTimeAndInterval)

object EGateBankCrunchTransformations {

  def groupEGatesIntoBanksWithSla(desksInBank: Int, sla: Int)(crunchResult: CrunchResult, workloads: Seq[Double]): CrunchResult = {
    val recommendedDesks = crunchResult.recommendedDesks.map(roundUpToNearestMultipleOf(desksInBank))
    val optimizerConfig = OptimizerConfig(sla)
    val simulationResult = TryRenjin.processWork(workloads, recommendedDesks, optimizerConfig)

    crunchResult.copy(
      recommendedDesks = recommendedDesks.map(recommendedDesk => recommendedDesk / desksInBank),
      waitTimes = simulationResult.waitTimes
    )
  }

  def roundUpToNearestMultipleOf(multiple: Int)(number: Int) = math.ceil(number.toDouble / multiple).toInt * multiple
}

abstract class CrunchActor(crunchPeriodHours: Int,
                           airportConfig: AirportConfig,
                           timeProvider: () => DateTime
                          ) extends Actor
  with ActorLogging
  with WorkloadsCalculator
  with CrunchCalculator
  with FlightState {

  var terminalQueueLatestCrunch: Map[TerminalName, Map[QueueName, CrunchResult]] = Map()

  val crunchCache: Cache[CrunchResultWithTimeAndInterval] = LruCache()

  def cacheCrunch[T](terminal: TerminalName, queue: QueueName): Future[CrunchResultWithTimeAndInterval] = {
    val key: String = cacheKey(terminal, queue)
    log.info(s"getting crunch for $key")
    crunchCache(key) {
      val crunch: Future[CrunchResultWithTimeAndInterval] = performCrunch(terminal, queue)
      crunch.onFailure { case failure => log.error(failure, s"Failure in calculating crunch for $key") }
      //todo un-future this mess
      val expensiveCrunchResult = Await.result(crunch, 15 seconds)
      expensiveCrunchResult
    }
  }

  def cacheKey[T](terminal: TerminalName, queue: QueueName): String = s"$terminal/$queue"

  def receive = {
    case PerformCrunchOnFlights(newFlights) =>
      onFlightUpdates(newFlights.toList, lastMidnightString)

      newFlights match {
        case Nil =>
          log.info("No crunch, no change")
        case _ =>
          reCrunchAllTerminalsAndQueues()
      }
    case SaveCrunchResult(tn, qn, crunchResult) =>
      saveNewCrunchResult(tn, qn, crunchResult)
    case GetLatestCrunch(terminalName, queueName) =>
      log.info(s"Received GetLatestCrunch($terminalName, $queueName)")
      val replyTo = sender()
      log.info(s"Sender is ${sender}")

      flights.values match {
        case Nil =>
          replyTo ! NoCrunchAvailable()
        case fs =>
          val futCrunch = cacheCrunch(terminalName, queueName)
          //          log.info(s"got keyed thingy ${futCrunch}")
          //todo this is NOT right
          futCrunch.value match {
            case Some(Success(cr: CrunchResultWithTimeAndInterval)) =>
              replyTo ! cr
            case Some(Failure(_)) =>
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

  def lastMidnightString: String = {
    val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
    timeProvider().toString(formatter)
  }

  def lastMidnight: DateTime = {
    val t = timeProvider()
    t.withTimeAtStartOfDay()
  }

  def reCrunchAllTerminalsAndQueues(): Unit = {
    for (tn <- airportConfig.terminalNames; qn <- airportConfig.queues) {
      val crunch: Future[CrunchResultWithTimeAndInterval] = performCrunch(tn, qn)
      crunch.onSuccess {
        case crunchResult =>
          self ! crunchResult
      }
    }
  }

  private def saveNewCrunchResult(tn: TerminalName, qn: QueueName, crunchResultWithTimeAndInterval: CrunchResultWithTimeAndInterval) = {
    log.info(s"crunch result for $tn/$qn")
    crunchCache.remove(cacheKey(tn, qn))
    crunchCache(cacheKey(tn, qn)) {
      log.info(s"returning crunch result for $tn/$qn")
      crunchResultWithTimeAndInterval
    }
  }

  def performCrunch(terminalName: TerminalName, queueName: QueueName): Future[CrunchResultWithTimeAndInterval] = {
    log.info("Performing a crunch")
    val flightsForAirportConfigTerminals = flights.values.filter(flight => airportConfig.terminalNames.contains(flight.Terminal)).toList
    val workloads: Future[TerminalQueueWorkLoads] = workLoadsByTerminal(Future(flightsForAirportConfigTerminals))

    val crunchStartTimeMillis = lastMidnight.getMillis
    val tq: QueueName = terminalName + "/" + queueName

    for (wl <- workloads) yield {
      val triedWl: Try[Map[String, List[Double]]] = Try {
        log.info(s"$tq lookup wl ")
        log.info(s"the workloads $wl")
        val terminalWorkloads = wl.get(terminalName)
        terminalWorkloads match {
          case Some(twl: Map[QueueName, Seq[WL]]) =>
            log.info(s"lastMidnight: $lastMidnight")
            val minutesRangeInMillis: NumericRange[Long] = WorkloadsHelpers.minutesForPeriod(crunchStartTimeMillis, crunchPeriodHours)
            log.info(s"^^^$tq filtering minutes to: ${minutesRangeInMillis.start} to ${minutesRangeInMillis.end}")
            val workloadsByQueue: Map[String, List[Double]] = WorkloadsHelpers.queueWorkloadsForPeriod(twl, minutesRangeInMillis)
            log.info(s"crunchStartTimeMillis: $crunchStartTimeMillis, crunchPeriod: $crunchPeriodHours")
            workloadsByQueue
          case None =>
            Map()
        }
      }

      val r: Try[CrunchResult] = triedWl.flatMap {
        (terminalWorkloads: Map[String, List[Double]]) =>
          log.info(s"Will crunch now $tq")
          log.info(s"terminalWorkloads are ${terminalWorkloads.keys}")
          val workloads: List[Double] = terminalWorkloads(queueName)
          log.info(s"$tq Crunching on ${workloads.take(50)}")
          val queueSla = airportConfig.slaByQueue(queueName)
          log.info(s"!!!@@@terminal workloads: $workloads")
          val crunchRes: Try[CrunchResult] = tryCrunch(terminalName, queueName, workloads, queueSla)
          log.info(s"Crunch complete for $tq ${crunchRes.map(x => CrunchResult(x.recommendedDesks.take(10), x.waitTimes.take(10)))}")
          if (queueName == "eGate")
            crunchRes.map(crunchResSuccess => {
              EGateBankCrunchTransformations.groupEGatesIntoBanksWithSla(5, queueSla)(crunchResSuccess, workloads)
            })
          else
            crunchRes
      }
      val asFuture = r match {
        case Success(s) =>
          log.info(s"Successful crunch for $tq starting at $crunchStartTimeMillis")
          CrunchResultWithTimeAndInterval(crunchStartTimeMillis, 60000L, s.recommendedDesks, s.waitTimes)
        case Failure(f) =>
          log.error(f, s"Failed to crunch $tq")
          throw f
      }
      asFuture
    }
  }
}
