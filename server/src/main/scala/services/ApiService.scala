package services

import java.util.Date

import actors.{EGateBankCrunchTransformations, GetLatestCrunch, GetPortWorkload}
import akka.actor.ActorRef
import akka.event.DiagnosticLoggingAdapter
import akka.pattern.AskableActorRef
import akka.util.Timeout
import controllers.{FixedPointPersistence, GetTerminalCrunch, ShiftPersistence, StaffMovementsPersistence}
import drt.shared.FlightsApi._
import drt.shared.PassengerSplits.{FlightNotFound, VoyagePaxSplits}
import drt.shared.Simulations.{QueueSimulationResult, TerminalSimulationResultsFull}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.query.FlightPassengerSplitsReportingService
import services.SDate.implicits._
import services.workloadcalculator.PaxLoadCalculator.queueWorkAndPaxLoadCalculator
import services.workloadcalculator.WorkloadCalculator

import scala.collection.JavaConversions._
import scala.collection.immutable.{NumericRange, Seq}
import scala.collection.immutable.{List, Map}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Codec
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

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
        //        println(s"boo ${l}");
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
  def processWork(airportConfig: AirportConfig)(terminalName: TerminalName, queueName: QueueName, workloads: List[Double], desks: List[Int]): QueueSimulationResult = {
    val optimizerConfig = OptimizerConfig(airportConfig.slaByQueue(queueName))

    if (queueName == "eGate")
      eGateSimulationResultForBanksAndWorkload(optimizerConfig, workloads, desks)
    else
      simulationResultForDesksAndWorkload(optimizerConfig, workloads, desks)
  }

  val gatesPerBank = 5
  val blockSize = 15
  val fillByBlockSize = List.fill[Int](blockSize) _

  def simulationResultForDesksAndWorkload(optimizerConfig: OptimizerConfig, workloads: List[Double], desks: List[Int]): QueueSimulationResult = {
    val desksPerMinute: List[Int] = desks.flatMap(x => fillByBlockSize(x))

    TryRenjin.runSimulationOfWork(workloads, desksPerMinute, optimizerConfig)
  }

  def eGateSimulationResultForBanksAndWorkload(optimizerConfig: OptimizerConfig, workloads: List[Double], desksPerBlock: List[Int]): QueueSimulationResult = {
    val egatesPerMinute: List[Int] = desksPerBlock.flatMap(d => fillByBlockSize(d * gatesPerBank))
    val simulationResult = TryRenjin.runSimulationOfWork(workloads, egatesPerMinute, optimizerConfig)
    val crunchRecBanksPerMinute = simulationResult.recommendedDesks.map(d => DeskRec(d.time, d.desks / gatesPerBank))
    simulationResult.copy(recommendedDesks = crunchRecBanksPerMinute)
  }
}

abstract class ApiService(val airportConfig: AirportConfig)
  extends Api
    with WorkloadCalculator
    with FlightsService
    with AirportToCountryLike
    with ShiftPersistence
    with FixedPointPersistence
    with StaffMovementsPersistence {

  override implicit val timeout: akka.util.Timeout = Timeout(5 seconds)

  val log = LoggerFactory.getLogger(this.getClass)

  def flightPassengerReporter: ActorRef
  def crunchStateActor: AskableActorRef

  val splitsCalculator = FlightPassengerSplitsReportingService.calculateSplits(flightPassengerReporter) _

  override def getWorkloads(): Future[Either[WorkloadsNotReady, PortPaxAndWorkLoads[QueuePaxAndWorkLoads]]] = {
    val workloadsFuture = crunchStateActor ? GetPortWorkload

    workloadsFuture.recover {
      case e: Throwable =>
        log.info(s"Didn't get the workloads: $e")
        WorkloadsNotReady()
    }.map {
      case WorkloadsNotReady() =>
        log.info(s"Got WorkloadsNotReady")
        Left(WorkloadsNotReady())
      case workloads: PortPaxAndWorkLoads[QueuePaxAndWorkLoads] =>
        log.info(s"Got the workloads")
        Right(workloads)

    }
  }

  override def processWork(terminalName: TerminalName, queueName: QueueName, workloads: List[Double], desks: List[Int]): QueueSimulationResult = {
    Try {
      WorkloadSimulation.processWork(airportConfig)(terminalName, queueName, workloads, desks)
    }.recover {
      case f =>
        log.error(s"Simulation failed on $terminalName/$queueName with $workloads and $desks", f)
        QueueSimulationResult(List(), Nil)
    }.get
  }

  def getTerminalSimulations(terminalName: TerminalName, workloads: Map[QueueName, List[Double]], desks: Map[QueueName, List[Int]]): TerminalSimulationResultsFull = {
    airportConfig.queues.getOrElse(terminalName, Seq()).collect {
      case queueName if queueName != Queues.Transfer =>
        val queueSimResults = Try {
          WorkloadSimulation.processWork(airportConfig)(terminalName, queueName, workloads.getOrElse(queueName, List()), desks.getOrElse(queueName, List()))
        }.recover {
          case f =>
            log.error(s"Simulation failed on $terminalName/$queueName with $workloads and $desks", f)
            QueueSimulationResult(List(), Nil)
        }.get
        queueName -> queueSimResults
    }.toMap
  }

  override def airportConfiguration() = airportConfig
}

trait LoggingCrunchCalculator extends CrunchCalculator with EGateBankCrunchTransformations {
  def airportConfig: AirportConfig

  def crunchPeriodHours: Int

  def log: DiagnosticLoggingAdapter

  def crunchQueueWorkloads(queueWorkloads: Seq[WL], terminalName: TerminalName, queueName: QueueName, crunchWindowStartTimeMillis: Long): CrunchResult = {
    val tq: QueueName = terminalName + "/" + queueName

    val minutesRangeInMillis: NumericRange[Long] = WorkloadsHelpers.minutesForPeriod(crunchWindowStartTimeMillis, crunchPeriodHours)
    val fullWorkloads: List[Double] = WorkloadsHelpers.queueWorkloadsForPeriod(queueWorkloads, minutesRangeInMillis)


    val queueSla = airportConfig.slaByQueue(queueName)

    val (minDesks, maxDesks) = airportConfig.minMaxDesksByTerminalQueue(terminalName)(queueName)
    val minDesksByMinute = minDesks.flatMap(d => List.fill[Int](60)(d))
    val maxDesksByMinute = maxDesks.flatMap(d => List.fill[Int](60)(d))

    val adjustedMinDesks = adjustDesksForEgates(queueName, minDesksByMinute)
    val adjustedMaxDesks = adjustDesksForEgates(queueName, maxDesksByMinute)

    log.info(s"Trying to crunch $terminalName / $queueName")

    val triedCrunchResult = tryCrunch(fullWorkloads, queueSla, adjustedMinDesks, adjustedMaxDesks)

    if (queueName == Queues.EGate)
      triedCrunchResult.map(crunchResSuccess => {
        groupEGatesIntoBanksWithSla(5, queueSla)(crunchResSuccess, fullWorkloads)
      })
    else
      triedCrunchResult

    triedCrunchResult match {
      case Success(OptimizerCrunchResult(deskRecs, waitTimes)) =>
        log.info(s"$tq Successful crunch for starting at $crunchWindowStartTimeMillis")
        CrunchResult(crunchWindowStartTimeMillis, 60000L, deskRecs, waitTimes)
      case Failure(f) =>
        log.warning(s"$tq Failed to crunch. ${f.getMessage}")
        throw f
    }
  }

  private def adjustDesksForEgates(queueName: QueueName, desksByMinute: List[Int]) = {
    if (queueName == Queues.EGate) {
      desksByMinute.map(_ * 5)
    } else {
      desksByMinute
    }
  }

  def tryCrunch(workloads: List[Double], sla: Int, minDesks: List[Int], maxDesks: List[Int]): Try[OptimizerCrunchResult] = {
    try {
      crunch(workloads, sla, minDesks, maxDesks)
    }
  }
}

trait CrunchCalculator {
  def crunch(workloads: List[Double], sla: Int, minDesks: List[Int], maxDesks: List[Int]): Try[OptimizerCrunchResult] = {
    val optimizerConfig = OptimizerConfig(sla)
    TryRenjin.crunch(workloads, minDesks, maxDesks, optimizerConfig)
  }
}

//trait ActorBackedCrunchService {
//  self: CrunchResultProvider =>
//  private val log: Logger = LoggerFactory.getLogger(getClass)
//  implicit val timeout: akka.util.Timeout
//
//  val crunchActor: AskableActorRef
//
//  def tryTQCrunch(terminalName: TerminalName, queueName: QueueName): Future[Either[NoCrunchAvailable, CrunchResult]] = {
//    log.info("Starting crunch latest request")
//    val crunchFuture: Future[Any] = crunchActor.ask(GetLatestCrunch(terminalName, queueName))
//
//    crunchFuture.recover {
//      case e: Throwable =>
//        log.info("Crunch not ready in time ", e)
//        Left(NoCrunchAvailable())
//    }.map {
//      case cr: CrunchResult =>
//        Right(cr)
//      case _ =>
//        Left(NoCrunchAvailable())
//    }
//  }
//}
