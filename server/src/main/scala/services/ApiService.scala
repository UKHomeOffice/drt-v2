package services

import java.util.Date

import actors.GetLatestCrunch
import akka.actor.ActorRef
import akka.event.{LoggingAdapter}
import akka.pattern.AskableActorRef
import akka.util.Timeout
import controllers.{ShiftPersistence, StaffMovementsPersistence}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.PassengerInfoRouterActor.FlightNotFound
import passengersplits.query.FlightPassengerSplitsReportingService
import spatutorial.shared.FlightsApi._
import spatutorial.shared.PassengerSplits.VoyagePaxSplits
import spatutorial.shared._
import spray.http.DateTime

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Codec
import scala.language.postfixOps
import scala.util.{Failure, Try}

trait AirportToCountryLike {
  lazy val airportInfo: Map[String, AirportInfo] = {
    val bufferedSource = scala.io.Source.fromURL(
      getClass.getResource("/airports.dat"))(Codec.UTF8)
    bufferedSource.getLines().map { l =>

      val t = Try {
        val splitRow: Array[String] = l.split(",")
        val sq: (String) => String = stripQuotes _
        AirportInfo(sq(splitRow(1)), sq(splitRow(2)), sq(splitRow(3)), sq(splitRow(4)))
      }
      t.getOrElse({
        println(s"boo ${l}");
        AirportInfo("failed on", l, "boo", "ya")
      })
    }.map(ai => (ai.code, ai)).toMap
  }

  def stripQuotes(row1: String): String = {
    row1.substring(1, row1.length - 1)
  }

  def airportInfoByAirportCode(code: String) = Future(airportInfo.get(code))

  def airportInfosByAirportCodes(codes: Set[String]): Future[Map[String, AirportInfo]] = Future {
    val res = codes.map(code =>
      (code, airportInfo.get(code))
    )
    val successes: Set[(String, AirportInfo)] = res collect {
      case (code, Some(ai)) =>
        (code, ai)
    }

    successes.toMap
  }
}

object AirportToCountry extends AirportToCountryLike {

}

object WorkloadSimulation {
  def processWork(airportConfig: AirportConfig)(terminalName: TerminalName, queueName: QueueName, workloads: List[Double], desks: List[Int]): SimulationResult = {
    val optimizerConfig = OptimizerConfig(airportConfig.slaByQueue(queueName))

    if (queueName == "eGate")
      eGateSimulationResultForBanksAndWorkload(optimizerConfig, workloads, desks)
    else
      simulationResultForDesksAndWorkload(optimizerConfig, workloads, desks)
  }

  def simulationResultForDesksAndWorkload(optimizerConfig: OptimizerConfig, workloads: List[Double], desks: List[Int]): SimulationResult = {
    val fulldesks: List[Int] = desks.flatMap(x => List.fill(15)(x))

    TryRenjin.processWork(workloads, fulldesks, optimizerConfig)
  }

  def eGateSimulationResultForBanksAndWorkload(optimizerConfig: OptimizerConfig, workloads: List[Double], desks: List[Int]): SimulationResult = {
    val fulldesks: List[Int] = desks.flatMap(x => List.fill(15)(x * 5))

    val simulationResult = TryRenjin.processWork(workloads, fulldesks, optimizerConfig)

    simulationResult.copy(recommendedDesks = simulationResult.recommendedDesks.map(d => DeskRec(d.time, d.desks / 5)))
  }
}

abstract class ApiService(airportConfig: AirportConfig)
  extends Api
    with WorkloadsCalculator
    with FlightsService
    with AirportToCountryLike
    with ActorBackedCrunchService
    with CrunchResultProvider
    with ShiftPersistence
    with StaffMovementsPersistence {

  override implicit val timeout: akka.util.Timeout = Timeout(5 seconds)

  val log = LoggerFactory.getLogger(this.getClass)
  log.info(s"ApiService.airportConfig = $airportConfig")

  def flightPassengerReporter: ActorRef

  val splitsCalculator = FlightPassengerSplitsReportingService.calculateSplits(flightPassengerReporter) _

  override def flightSplits(portCode: String, flightCode: String, scheduledDateTime: MilliDate): Future[VoyagePaxSplits] = {
    val splits: Future[Any] = splitsCalculator(airportConfig.portCode, "T1", flightCode, DateTime(scheduledDateTime.millisSinceEpoch))
    splits.map(v => v match {
      case value: VoyagePaxSplits =>
        log.info(s"Found flight split for $portCode/$flightCode/${scheduledDateTime}")
        value
      case FlightNotFound(cc, fc, sadt) =>
        throw new Exception(s"Did not find flight $cc $fc $sadt")
      case Failure(f) =>
        throw f
    }
    )
  }

  override def getWorkloads(): Future[TerminalQueuePaxAndWorkLoads] = {
    val flightsFut: Future[List[ApiFlight]] = getFlights(0, 0)
    val flightsForTerminalsWeCareAbout = flightsFut.map { allFlights =>
      log.info(s"AirportConfig: $airportConfig")
      val names: Set[TerminalName] = airportConfig.terminalNames.toSet
      allFlights.filter(flight => {
        names.contains(flight.Terminal)
      })
    }
    workAndPaxLoadsByTerminal(flightsForTerminalsWeCareAbout)
  }

  override def welcomeMsg(name: String): String = {
    println("welcomeMsg")
    s"Welcome to SPA, $name! Time is now ${new Date}"
  }

  override def processWork(terminalName: TerminalName, queueName: QueueName, workloads: List[Double], desks: List[Int]): SimulationResult = {
    WorkloadSimulation.processWork(airportConfig)(terminalName, queueName, workloads, desks)
  }

  override def airportConfiguration() = airportConfig
}

trait CrunchCalculator {
  //  self: HasAirportConfig =>
  def log: LoggingAdapter

  def tryCrunch(terminalName: TerminalName, queueName: String, workloads: List[Double], sla: Int): Try[OptimizerCrunchResult] = {
    log.info(s"Crunch requested for $terminalName, $queueName, Workloads: ${workloads.take(15).mkString("(", ",", ")")}...")
    val repeat = List.fill[Int](workloads.length) _
    val optimizerConfig = OptimizerConfig(sla)
    //todo take the maximum desks from some durable store
    val minimumDesks: List[Int] = repeat(2)
    val maximumDesks: List[Int] = repeat(25)
    TryRenjin.crunch(workloads, minimumDesks, maximumDesks, optimizerConfig)
  }
}

trait CrunchResultProvider {
  def tryCrunch(terminalName: TerminalName, queueName: QueueName): Future[Either[NoCrunchAvailable, CrunchResult]]
}

trait ActorBackedCrunchService {
  self: CrunchResultProvider =>
  private val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val timeout: akka.util.Timeout
  val crunchActor: AskableActorRef

  def tryCrunch(terminalName: TerminalName, queueName: QueueName): Future[Either[NoCrunchAvailable, CrunchResult]] = {
    log.info("Starting crunch latest request")
    val result: Future[Any] = crunchActor ? GetLatestCrunch(terminalName, queueName)
    result.recover {
      case e: Throwable =>
        log.error("Crunch not ready ", e)
        Left(NoCrunchAvailable())
    }.map {
      case cr: CrunchResult =>
        Right(cr)
      case _ =>
        Left(NoCrunchAvailable())
    }
  }
}
