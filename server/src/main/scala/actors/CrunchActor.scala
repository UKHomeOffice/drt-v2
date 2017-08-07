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

import scala.collection.immutable
import scala.collection.immutable.{NumericRange, Seq}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


//i'm of two minds about the benefit of having this message independent of the Flights() message.
case class PerformCrunchOnFlights(flights: Seq[Arrival])

case class GetLatestCrunch(terminalName: TerminalName, queueName: QueueName)

case class SaveTerminalCrunchResult(terminalName: TerminalName, terminalCrunchResult: Map[TerminalName, CrunchResult])

trait EGateBankCrunchTransformations {

  def groupEGatesIntoBanksWithSla(desksInBank: Int, sla: Int)(crunchResult: OptimizerCrunchResult, workloads: Seq[Double]): OptimizerCrunchResult = {
    val recommendedDesks = crunchResult.recommendedDesks.map(roundUpToNearestMultipleOf(desksInBank))
    val optimizerConfig = OptimizerConfig(sla)
    val simulationResult = runSimulation(workloads, recommendedDesks, optimizerConfig)

    crunchResult.copy(
      recommendedDesks = recommendedDesks.map(recommendedDesk => recommendedDesk / desksInBank),
      waitTimes = simulationResult.waitTimes
    )
  }

  protected[actors] def runSimulation(workloads: Seq[Double], recommendedDesks: immutable.IndexedSeq[Int], optimizerConfig: OptimizerConfig) = {
    TryRenjin.runSimulationOfWork(workloads, recommendedDesks, optimizerConfig)
  }

  def roundUpToNearestMultipleOf(multiple: Int)(number: Int) = math.ceil(number.toDouble / multiple).toInt * multiple
}

object EGateBankCrunchTransformations extends EGateBankCrunchTransformations

object TimeZone {
  def lastLocalMidnightOn(now: DateTime) = now.toLocalDate.toDateTimeAtStartOfDay(localTimeZone)

  def localTimeZone = DateTimeZone.forID("Europe/London")
}

abstract class CrunchActor(override val crunchPeriodHours: Int,
                           override val airportConfig: AirportConfig,
                           timeProvider: () => DateTime
                          ) extends Actor
  with DiagnosticActorLogging
  with WorkloadCalculator
  with LoggingCrunchCalculator
  with FlightState
  with DomesticPortList {

  log.info(s"airportConfig is $airportConfig")
  var terminalQueueLatestCrunch: Map[TerminalName, Map[QueueName, CrunchResult]] = Map()

  val crunchCache: Cache[Option[Map[QueueName, CrunchResult]]] = LruCache(50, 16, 30 seconds)

  def cacheCrunch[T](terminal: TerminalName): Future[Option[Map[QueueName, CrunchResult]]] = {
    val key: String = cacheKey(terminal)
    log.info(s"getting crunch for $key")
    crunchCache(key) {
      log.info(s"updating cache for $key")
      val crunch: Future[Option[Map[QueueName, CrunchResult]]] = performTerminalCrunch(terminal)
      crunch.onFailure { case failure =>
        log.error(failure, s"Failure calculating crunch for $key")
        log.warning(s"Failure in calculating crunch for $key. ${failure.getMessage} ${failure.toString()}")
      }

      //todo un-future this mess
      val expensiveCrunchResult = Await.result(crunch, 1 minute)
      expensiveCrunchResult
    }

  }

  def cacheKey[T](terminal: TerminalName): String = s"$terminal"

  def receive = {
    case PerformCrunchOnFlights(newFlights) =>
      onFlightUpdates(newFlights.toList, lastLocalMidnightString, domesticPorts)

      newFlights match {
        case Nil => log.info("No crunch, no change")
        case _ => reCrunchAllTerminals()
      }
    case SaveTerminalCrunchResult(tn, crunchResult) =>
      log.info(s"Should SaveCrunchResult for $tn")
      saveTerminalCrunchResult(tn, crunchResult)
    case GetLatestCrunch(_, Queues.Transfer) =>
      sender ! NoCrunchAvailable()
    case GetLatestCrunch(terminalName, queueName) =>
      log.info(s"Received GetLatestCrunch($terminalName, $queueName)")
      val replyTo = sender()
      log.info(s"Sender is ${sender}")
      flightState.values match {
        case Nil =>
          log.info("No flights, no crunch")
          replyTo ! NoCrunchAvailable()
        case fs =>
          val futCrunch: Future[Option[Map[QueueName, CrunchResult]]] = cacheCrunch(terminalName)
          //          log.info(s"got keyed thingy ${futCrunch}")
          //todo this is NOT right
          futCrunch.value match {
            case Some(Success(optionalCrunchResult)) =>
              optionalCrunchResult match {
                case Some(terminalCrunchResult: Map[QueueName, CrunchResult]) =>
                  val queueCrunchResult = terminalCrunchResult.getOrElse(queueName, NoCrunchAvailable())
                  log.info(s"Sending crunch result for $terminalName / $queueName: $queueCrunchResult")
                  replyTo ! queueCrunchResult
                case None =>
                  log.info(s"Sending crunch result for $terminalName / $queueName: NoCrunchAvailable")
                  replyTo ! NoCrunchAvailable
              }
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

  def reCrunchAllTerminals(): Unit = {
    airportConfig.terminalNames.foreach(performTerminalCrunch)
  }

  private def saveTerminalCrunchResult(tn: TerminalName, terminalCrunchResults: Map[QueueName, CrunchResult]) = {
    log.info(s"saving new crunch result for $tn")
    crunchCache.remove(cacheKey(tn))
    crunchCache(cacheKey(tn)) {
      log.info(s"returning crunch result for $tn")
      Option(terminalCrunchResults)
    }
  }

  val uniqueArrivalsWithCodeshares = CodeShares.uniqueArrivalsWithCodeshares(identity[Arrival]) _

  def performTerminalCrunch(terminalName: TerminalName): Future[Option[Map[QueueName, CrunchResult]]] = {
    val flightsForTerminal = flightState.values.filter(flight => flight.Terminal == terminalName).toList
    val uniqueArrivals = uniqueArrivalsWithCodeshares(flightsForTerminal).map(_._1)
    val crunchWindowStartTimeMillis = lastLocalMidnight.getMillis
    val workloadsFuture: Future[TerminalPaxAndWorkLoads[Seq[WL]]] = terminalQueueLoads[Seq[WL]](
      terminalName,
      Future(uniqueArrivals),
      PaxLoadCalculator.queueWorkLoadCalculator)

    log.info(s"lastMidnight: $lastLocalMidnight")

    val queuesToCrunch = airportConfig.queues(terminalName).filterNot(_ == Queues.Transfer)

    val queueCrunchFuture = workloadsFuture.map(twl => {
      val twlNoTransfers = twl.filterNot {
        case (qn, _) => qn == Queues.Transfer
      }
      log.info(s"twl: ${twlNoTransfers.size} vs ${queuesToCrunch.length}")
      if (twlNoTransfers.size == queuesToCrunch.length) {
        Option(twlNoTransfers.map {
          case (qn, qwl) =>
            crunchQueueWorkloads(qwl, terminalName, qn, crunchWindowStartTimeMillis)
        }.toList)
      } else None
    })

    val terminalCrunchFuture = queueCrunchFuture.map(optionalQueueCrunches => {
      optionalQueueCrunches.map(queueCrunches => {
        val terminalCrunchResult = queuesToCrunch.zip(queueCrunches).toMap
        self ! SaveTerminalCrunchResult(terminalName, terminalCrunchResult)
        terminalCrunchResult
      })
    })

    terminalCrunchFuture
  }
}
