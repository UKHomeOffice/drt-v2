package services

import actors.pointInTime.CrunchStateReadActor
import actors.{CachableActorQuery, GetPortWorkload, GetState}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.util.Timeout
import controllers.{FixedPointPersistence, ShiftPersistence, StaffMovementsPersistence}
import drt.shared.Crunch.{CrunchState, MillisSinceEpoch}
import drt.shared.FlightsApi._
import drt.shared.Simulations.{QueueSimulationResult, TerminalSimulationResultsFull}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.{List, Map, Seq}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Codec
import scala.language.postfixOps
import scala.util.Try

trait AirportToCountryLike {
  lazy val airportInfo: Map[String, AirportInfo] = {
    val bufferedSource = scala.io.Source.fromURL(
      getClass.getResource("/airports.dat"))(Codec.UTF8)
    bufferedSource.getLines().map { l =>

      val t = Try {
        val splitRow: Array[String] = l.split(",")
        val sq: (String) => String = stripQuotes
        AirportInfo(sq(splitRow(1)), sq(splitRow(2)), sq(splitRow(3)), sq(splitRow(4)))
      }
      t.getOrElse({
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

abstract class ApiService(val airportConfig: AirportConfig,
                          val shiftsActor: ActorRef,
                          val fixedPointsActor: ActorRef,
                          val staffMovementsActor: ActorRef)
  extends Api
    with FlightsService
    with AirportToCountryLike
    with ShiftPersistence
    with FixedPointPersistence
    with StaffMovementsPersistence {

  override implicit val timeout: akka.util.Timeout = Timeout(5 seconds)

  override val log: Logger = LoggerFactory.getLogger(this.getClass)

  def crunchStateActor: AskableActorRef
  def actorSystem: ActorSystem

  def askableCacheActorRef: AskableActorRef

  override def getWorkloads(pointInTime: Long): Future[Either[WorkloadsNotReady, PortLoads]] = {
    val workloadsFuture = if (pointInTime > 0) {
      val query = CachableActorQuery(Props(classOf[CrunchStateReadActor], SDate(pointInTime.toLong), airportConfig.queues), GetPortWorkload)
      askableCacheActorRef ? query
    } else {
      crunchStateActor ? GetPortWorkload
    }

    workloadsFuture.recover {
      case e: Throwable =>
        log.info(s"Didn't get the workloads: $e")
        WorkloadsNotReady()
    }.map {
      case WorkloadsNotReady() =>
        log.info(s"Got WorkloadsNotReady")
        Left(WorkloadsNotReady())
      case PortLoads(workloads) =>
        log.info(s"Got the workloads")
        workloads.foreach {
          case (tn, tl) => tl.foreach {
            case (qn, (wl, pl)) => log.info(s"workloads: $tn/$qn wl: ${wl.length}, pl: ${pl.length} ")
          }
        }
        Right(PortLoads(workloads))
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

  def airportConfiguration(): AirportConfig = airportConfig

  def getCrunchState(pointIntTime: MillisSinceEpoch): Future[Option[CrunchState]] = {
    crunchStateActor.ask(GetState)(new Timeout(30 second)).map {
      case None => None
      case Some(cs: CrunchState) => Option(cs)
    }
  }
}

