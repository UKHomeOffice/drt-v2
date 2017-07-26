package services

import java.util.Date

import actors.{EGateBankCrunchTransformations, GetLatestCrunch}
import akka.actor.ActorRef
import akka.event.DiagnosticLoggingAdapter
import akka.pattern.AskableActorRef
import akka.util.Timeout
import controllers.{FixedPointPersistence, ShiftPersistence, StaffMovementsPersistence}
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
  val fillByBlockSize = List.fill[Int](blockSize)_

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
    with ActorBackedCrunchService
    with CrunchResultProvider
    with ShiftPersistence
    with FixedPointPersistence
    with StaffMovementsPersistence {

  override implicit val timeout: akka.util.Timeout = Timeout(5 seconds)

  val log = LoggerFactory.getLogger(this.getClass)

  def flightPassengerReporter: ActorRef

  val splitsCalculator = FlightPassengerSplitsReportingService.calculateSplits(flightPassengerReporter) _

  override def flightSplits(portCode: String, flightCode: String, scheduledDateTime: MilliDate): Future[Either[FlightNotFound, VoyagePaxSplits]] = {
    val splits: Future[Any] = splitsCalculator(airportConfig.portCode, "T1", flightCode, scheduledDateTime)
    splits.map(v => v match {
      case value: VoyagePaxSplits =>
        log.info(s"Found flight split for $portCode/$flightCode/${scheduledDateTime}")
        Right(value)
      case fnf: FlightNotFound =>
        Left(fnf)
      case Failure(f) =>
        throw f
    })
  }

  override def getWorkloads(): Future[TerminalQueuePaxAndWorkLoads[QueuePaxAndWorkLoads]] = {
    log.info("getting workloads")
    val flightsFut: Future[List[Arrival]] = getFlights(0, 0)

    val bestPax: (Arrival) => Int = BestPax(airportConfig.portCode)


    val flightsForTerminalsWeCareAbout = flightsFut.map { allFlights =>
      val names: Set[TerminalName] = airportConfig.terminalNames.toSet
      allFlights.filter(flight => {
        names.contains(flight.Terminal)
      })
    }

    for {flights <- flightsForTerminalsWeCareAbout
         flightsByTerminal: Map[String, List[Arrival]] = flights.groupBy(_.Terminal)
    } {
      val paxByTerminal = flightsByTerminal.mapValues((arrivals: List[Arrival]) => arrivals.map(bestPax(_)).sum)
      log.debug(s"paxByTerminal: ${paxByTerminal}")
    }

    val qlByT = queueLoadsByTerminal[QueuePaxAndWorkLoads](flightsForTerminalsWeCareAbout, queueWorkAndPaxLoadCalculator)

    qlByT.onComplete {
      case Success(s) => log.debug(s"qlByT $s")
      case Failure(r) => log.error(s"qlByT $r")
    }
    log.info(s"returning workloads future")
    qlByT
  }

  override def welcomeMsg(name: String): String = {
    println("welcomeMsg")
    s"Welcome to SPA, $name! Time is now ${new Date}"
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

  def crunchWorkloads(workloads: Future[TerminalQueuePaxAndWorkLoads[Seq[WL]]], terminalName: TerminalName, queueName: QueueName, crunchWindowStartTimeMillis: Long): Future[CrunchResult] = {
    val tq: QueueName = queueName
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
          val queueSla = airportConfig.slaByQueue(queueName)

          val (minDesks, maxDesks) = airportConfig.minMaxDesksByTerminalQueue(terminalName)(queueName)
          val minDesksByMinute = minDesks.flatMap(d => List.fill[Int](60)(d))
          val maxDesksByMinute = maxDesks.flatMap(d => List.fill[Int](60)(d))

          val adjustedMinDesks = adjustDesksForEgates(queueName, minDesksByMinute)
          val adjustedMaxDesks = adjustDesksForEgates(queueName, maxDesksByMinute)

          val crunchRes = tryCrunch(terminalName, queueName, workloads, queueSla, adjustedMinDesks, adjustedMaxDesks)
          if (queueName == Queues.EGate)
            crunchRes.map(crunchResSuccess => {
              groupEGatesIntoBanksWithSla(5, queueSla)(crunchResSuccess, workloads)
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


  private def adjustDesksForEgates(queueName: QueueName, desksByMinute: List[Int]) = {
    if (queueName == Queues.EGate) {
      desksByMinute.map(_ * 5)
    } else {
      desksByMinute
    }
  }

  def tryCrunch(terminalName: TerminalName, queueName: String, workloads: List[Double], sla: Int, minDesks: List[Int], maxDesks: List[Int]): Try[OptimizerCrunchResult] = {
    log.info(s"Crunch requested for $terminalName, $queueName")
    val mdc = log.getMDC
    val newMdc = Map("terminalQueue" -> s"$terminalName/$queueName")
    log.setMDC(newMdc)
    try {
      crunch(terminalName, queueName, workloads, sla, minDesks, maxDesks)
    } finally {
      log.setMDC(mdc)
    }
  }
}

trait CrunchCalculator {
  def crunch(terminalName: TerminalName, queueName: String, workloads: List[Double], sla: Int, minDesks: List[Int], maxDesks: List[Int]): Try[OptimizerCrunchResult] = {
    val optimizerConfig = OptimizerConfig(sla)
    TryRenjin.crunch(workloads, minDesks, maxDesks, optimizerConfig)
  }
}


trait CrunchResultProvider {
  def tryCrunch(terminalName: TerminalName, queueName: QueueName): Future[Either[NoCrunchAvailable, CrunchResult]]

  def tryTQCrunch(terminalName: TerminalName, queueName: QueueName): Future[Either[NoCrunchAvailable, CrunchResult]]
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
        log.info("Crunch not ready in time ", e)
        Left(NoCrunchAvailable())
    }.map {
      case cr: CrunchResult =>
        Right(cr)
      case _ =>
        Left(NoCrunchAvailable())
    }
  }

  def tryTQCrunch(terminalName: TerminalName, queueName: QueueName): Future[Either[NoCrunchAvailable, CrunchResult]] = {
    log.info("Starting crunch latest request")
    val crunchFuture: Future[Any] = crunchActor ? GetLatestCrunch(terminalName, queueName)

    crunchFuture.recover {
      case e: Throwable =>
        log.info("Crunch not ready in time ", e)
        Left(NoCrunchAvailable())
    }.map {
      case cr: CrunchResult =>
        Right(cr)
      case _ =>
        Left(NoCrunchAvailable())
    }
  }
}
