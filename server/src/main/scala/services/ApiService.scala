package services

import java.util.{Date, UUID}

import akka.actor.ActorRef
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.AskableActorRef
import controllers.GetLatestCrunch
import org.slf4j.{Logger, LoggerFactory}
import services.workloadcalculator.PassengerQueueTypes
import spatutorial.shared._
import spatutorial.shared.FlightsApi._

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.io.Codec
import scala.util.{Success, Try}
import spatutorial.shared.HasAirportConfig

//  case class Row(id: Int, city: String, city2: String, country: String, code1: String, code2: String, loc1: Double,
//                 loc2: Double, elevation: Double,dkDouble: Double, dk: String, tz: String)


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

//trait LegacyCrunchOnDemand {
//
//  private val log = LoggerFactory.getLogger(getClass)
//
//  def tryCrunch(terminalName: TerminalName, queueName: String, workloads: List[Double], airportConfig: AirportConfig): Try[CrunchResult] = {
//    log.info(s"Crunch requested for $terminalName, $queueName, Workloads: ${workloads.take(15).mkString("(", ",", ")")}...")
//    val repeat = List.fill[Int](workloads.length) _
//    val optimizerConfig = OptimizerConfig(airportConfig.slaByQueue(queueName))
//    //todo take the maximum desks from some durable store
//    val minimumDesks: List[Int] = repeat(2)
//    val maximumDesks: List[Int] = repeat(25)
//    TryRenjin.crunch(workloads, minimumDesks, maximumDesks, optimizerConfig)
//  }
//}

abstract class ApiService(airportConfig: AirportConfig)
  extends Api
    with WorkloadsCalculator
    with FlightsService
    with AirportToCountryLike
    with ActorBackedCrunchService
    with CrunchResultProvider {

  type WorkloadByTerminalQueue = Map[TerminalName, Map[QueueName, (Seq[WL], Seq[Pax])]]
  val log = LoggerFactory.getLogger(this.getClass)
  log.info(s"ApiService.airportConfig = $airportConfig")
  override def getWorkloads(): Future[WorkloadByTerminalQueue] = {
    val flightsFut: Future[List[ApiFlight]] = getFlights(0, 0)
    val flightsForTerminalsWeCareAbout = flightsFut.map { allFlights =>
      log.info(s"AirportConfig: $airportConfig")
      val names: Set[TerminalName] = airportConfig.terminalNames.toSet
      allFlights.filter(flight => {
        names.contains(flight.Terminal)
      })
    }
    getWorkloadsByTerminal(flightsForTerminalsWeCareAbout)
  }

  override def welcomeMsg(name: String): String = {
    println("welcomeMsg")
    s"Welcome to SPA, $name! Time is now ${new Date}"
  }

  override def processWork(terminalName: TerminalName, queueName: QueueName, workloads: List[Double], desks: List[Int]): SimulationResult = {
    val fulldesks: List[Int] = desks.flatMap(x => List.fill(15)(x))

    val optimizerConfig = OptimizerConfig(airportConfig.slaByQueue(queueName))
    TryRenjin.processWork(workloads, fulldesks, optimizerConfig)
  }

  override def airportConfiguration() = airportConfig
}

trait CrunchCalculator {
  //  self: HasAirportConfig =>
  def log: LoggingAdapter

  def tryCrunch(terminalName: TerminalName, queueName: String, workloads: List[Double], sla: Int): Try[CrunchResult] = {
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
    }
  }
}

//abstract class ApiService
//  extends Api
//    with WorkloadsService
//    with CrunchCalculator
//    with ActorBackedCrunchService
//    with FlightsService
//    with AirportToCountryLike
//    with AirportConfig
//    with CrunchResultProvider {
//  config: HasAirportConfig =>
//
//
//  //  val log: LoggingAdapter = Logging.getLogger(this)
//  ////  var todos: List[DeskRecTimeslot] = Nil
//
//  def crunch(terminalName: TerminalName, queueName: QueueName, workloads: List[Double]) = {
//    Future.fromTry(tryCrunch(terminalName, queueName, workloads))
//  }
//
//  def getLatestCrunch(terminalName: TerminalName, queueName: QueueName): Future[CrunchResult] = {
//    tryCrunch(terminalName, queueName)
//  }
//
//  override def welcomeMsg(name: String): String = {
//    println("welcomeMsg")
//    s"Welcome to SPA, $name! Time is now ${
//      new Date
//    }"
//  }
//
//
//  override def processWork(terminalName: TerminalName, queueName: QueueName, workloads: List[Double], desks: List[Int]): SimulationResult = {
//    val fulldesks: List[Int] = desks.flatMap(x => List.fill(15)(x))
//
//    val optimizerConfig = OptimizerConfig(airportConfig.slaByQueue(queueName))
//    TryRenjin.processWork(workloads, fulldesks, optimizerConfig)
//  }
//
//  def airportConfiguration: AirportConfig = {
//    config.airportConfig
//  }
//airportConfig}
