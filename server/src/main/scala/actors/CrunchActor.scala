package actors

import akka.actor._
import controllers.FlightState
import drt.shared.FlightsApi._
import drt.shared.{Arrival, _}
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.DateTimeFormat
import services._
import services.workloadcalculator.{PaxLoadCalculator, WorkloadCalculator}
import spray.caching.{Cache, LruCache}

import scala.collection.immutable.{NumericRange, Seq}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


//i'm of two minds about the benefit of having this message independent of the Flights() message.
case class PerformCrunchOnFlights(flights: Seq[Arrival])

case class GetLatestCrunch(terminalName: TerminalName, queueName: QueueName)

case class SaveCrunchResult(terminalName: TerminalName, queueName: QueueName, crunchResult: CrunchResult)

object EGateBankCrunchTransformations {

  def groupEGatesIntoBanksWithSla(desksInBank: Int, sla: Int)(crunchResult: OptimizerCrunchResult, workloads: Seq[Double]): OptimizerCrunchResult = {
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

object TimeZone {
  def lastLocalMidnightOn(now: DateTime) = now.toLocalDate.toDateTimeAtStartOfDay(localTimeZone)

  def localTimeZone = DateTimeZone.forID("Europe/London")
}

abstract class CrunchActor(crunchPeriodHours: Int,
                           airportConfig: AirportConfig,
                           timeProvider: () => DateTime
                          ) extends Actor
  with DiagnosticActorLogging
  with WorkloadCalculator
  with LoggingCrunchCalculator
  with FlightState
  with DomesticPortList {

  log.info(s"airportConfig is $airportConfig")
  var terminalQueueLatestCrunch: Map[TerminalName, Map[QueueName, CrunchResult]] = Map()

  val crunchCache: Cache[CrunchResult] = LruCache()

  def cacheCrunch[T](terminal: TerminalName, queue: QueueName): Future[CrunchResult] = {
    val key: String = cacheKey(terminal, queue)
    log.info(s"getting crunch for $key")
    crunchCache(key) {
      val crunch: Future[CrunchResult] = performCrunch(terminal, queue)
      crunch.onFailure { case failure =>
        log.error(failure, s"Failure calculating crunch for $key")
        log.warning(s"Failure in calculating crunch for $key. ${failure.getMessage} ${failure.toString()}")
      }

      //todo un-future this mess
      val expensiveCrunchResult = Await.result(crunch, 1 minute)
      expensiveCrunchResult
    }
  }

  def cacheKey[T](terminal: TerminalName, queue: QueueName): String = s"$terminal/$queue"

  def receive = {
    case PerformCrunchOnFlights(newFlights) =>
      onFlightUpdates(newFlights.toList, lastLocalMidnightString, domesticPorts)

      newFlights match {
        case Nil =>
          log.info("No crunch, no change")
        case _ =>
          reCrunchAllTerminalsAndQueues()
      }
    case SaveCrunchResult(tn, qn, crunchResult) =>
      log.info(s"Should SaveCrunchResult for $tn/$qn")
      saveNewCrunchResult(tn, qn, crunchResult)
    case GetLatestCrunch(terminalName, queueName) =>
      log.info(s"Received GetLatestCrunch($terminalName, $queueName)")
      val replyTo = sender()
      log.info(s"Sender is ${sender}")

      flights.values match {
        case Nil =>
          replyTo ! NoCrunchAvailable()
        case fs =>
          val futCrunch: Future[CrunchResult] = cacheCrunch(terminalName, queueName)
          //          log.info(s"got keyed thingy ${futCrunch}")
          //todo this is NOT right
          futCrunch.value match {
            case Some(Success(cr: CrunchResult)) =>
              replyTo ! cr
            case Some(Failure(_)) =>
              log.info(s"unsuccessful crunch here $terminalName/$queueName")
              replyTo ! NoCrunchAvailable()
            case None =>
              log.info(s"got nothing $terminalName/$queueName")
              replyTo ! NoCrunchAvailable()
          }
      }
    case message =>
      log.error(s"crunchActor received unhandled ${message}")
  }

  def lastLocalMidnightString: String = {
    val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
    // todo this function needs more work to make it a sensible cut off time
    lastLocalMidnight.toString(formatter)
  }

  def lastLocalMidnight: DateTime = {
    val now = timeProvider()
    TimeZone.lastLocalMidnightOn(now)
  }

  def reCrunchAllTerminalsAndQueues(): Unit = {
    for {
      tn <- airportConfig.terminalNames
      qn <- airportConfig.queues(tn)
    } {
      val crunch: Future[CrunchResult] = performCrunch(tn, qn)
//      crunchCache.get(cacheKey(tn, qn)) match {
//        case None => crunchCache(cacheKey(tn, qn)) {
//          crunch
//        }
//        case _ =>
//      }
      crunch.onSuccess {
        case crunchResult =>
          self ! SaveCrunchResult(tn, qn, crunchResult)
      }
    }
  }

  private def saveNewCrunchResult(tn: TerminalName, qn: QueueName, crunchResultWithTimeAndInterval: CrunchResult) = {
    log.info(s"saving new crunch result for $tn/$qn")
    crunchCache.remove(cacheKey(tn, qn))
    crunchCache(cacheKey(tn, qn)) {
      log.info(s"returning crunch result for $tn/$qn")
      crunchResultWithTimeAndInterval
    }
  }

  val uniqueArrivalsWithCodeshares = CodeShares.uniqueArrivalsWithCodeshares(identity[Arrival]) _

  def performCrunch(terminalName: TerminalName, queueName: QueueName): Future[CrunchResult] = {
    val tq: QueueName = terminalName + "/" + queueName
    log.info(s"$tq Performing a crunch")
    val flightsForAirportConfigTerminals = flights.values.filter(flight => flight.Terminal == terminalName).toList
    val uniqueArrivals = uniqueArrivalsWithCodeshares(flightsForAirportConfigTerminals).map(_._1)
    val workloads: Future[TerminalQueuePaxAndWorkLoads[Seq[WL]]] = queueLoadsByTerminal[Seq[WL]](
      Future(uniqueArrivals),
      PaxLoadCalculator.queueWorkLoadCalculator)

    val crunchWindowStartTimeMillis = lastLocalMidnight.getMillis
    log.info(s"$tq lastMidnight: $lastLocalMidnight")

    crunchWorkloads(workloads, terminalName, queueName, crunchWindowStartTimeMillis)
  }

  def crunchWorkloads(workloads: Future[TerminalQueuePaxAndWorkLoads[Seq[WL]]], terminalName: TerminalName, queueName: QueueName, crunchWindowStartTimeMillis: Long): Future[CrunchResult] = {
    val tq: QueueName = terminalName + "/" + queueName
    for (wl <- workloads) yield {
      val triedWl: Try[Map[String, List[Double]]] = Try {
        log.info(s"$tq lookup wl ")
        val terminalWorkloads = wl.get(terminalName)
        terminalWorkloads match {
          case Some(twl: Map[QueueName, Seq[WL]]) =>
            val minutesRangeInMillis: NumericRange[Long] = WorkloadsHelpers.minutesForPeriod(crunchWindowStartTimeMillis, crunchPeriodHours)
            log.info(s"$tq filtering minutes to: ${minutesRangeInMillis.start} to ${minutesRangeInMillis.end}")
            val workloadsByQueue: Map[String, List[Double]] = WorkloadsHelpers.queueWorkloadsForPeriod(twl, minutesRangeInMillis)
            log.info(s"$tq crunchWindowStartTimeMillis: $crunchWindowStartTimeMillis, crunchPeriod: $crunchPeriodHours")
            workloadsByQueue
          case None =>
            Map()
        }
      }

      val r: Try[OptimizerCrunchResult] = triedWl.flatMap {
        (terminalWorkloads: Map[String, List[Double]]) =>
          log.info(s"$tq terminalWorkloads are ${terminalWorkloads.keys}")
          val workloads: List[Double] = terminalWorkloads(queueName)
          log.info(s"$tq Crunching on terminal workloads: ${workloads.take(50)}")
          val queueSla = airportConfig.slaByQueue(queueName)

          val (minDesks, maxDesks) = airportConfig.minMaxDesksByTerminalQueue(terminalName)(queueName)
          val minDesksByMinute = minDesks.flatMap(d => List.fill[Int](60)(d))
          val maxDesksByMinute = maxDesks.flatMap(d => List.fill[Int](60)(d))

          val crunchRes = tryCrunch(terminalName, queueName, workloads, queueSla, minDesksByMinute, maxDesksByMinute)
          if (queueName == "eGate")
            crunchRes.map(crunchResSuccess => {
              EGateBankCrunchTransformations.groupEGatesIntoBanksWithSla(5, queueSla)(crunchResSuccess, workloads)
            })
          else
            crunchRes
      }
      val asFuture = r match {
        case Success(s) =>
          log.info(s"$tq Successful crunch for starting at $crunchWindowStartTimeMillis")
          val res = CrunchResult(crunchWindowStartTimeMillis, 60000L, s.recommendedDesks, s.waitTimes)
          log.info(s"$tq Crunch complete")
          res
        case Failure(f) =>
          log.warning(s"$tq Failed to crunch. ${f.getMessage}")
          throw f
      }
      asFuture
    }
  }
}
