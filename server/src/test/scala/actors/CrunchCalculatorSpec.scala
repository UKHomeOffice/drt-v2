package actors

import akka.event.DiagnosticLoggingAdapter
import drt.shared.FlightsApi.{TerminalName, TerminalQueuePaxAndWorkLoads}
import drt.shared._
import org.specs2.Specification
import org.specs2.mock.Mockito
import services.{LoggingCrunchCalculator, OptimizerConfig, OptimizerCrunchResult}

import scala.collection.immutable.{IndexedSeq, Seq}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Try}

class CrunchCalculatorSpec extends Specification with Mockito {

  def bankSize = 5

  override def is =
    s2"""
        | When crunching eDesks, we deal with 'banks' rather than desks in the UI, and the config
        |  The number of banks in airportConfig
        |    - minDesks should be multiplied by bankSize of 5 when crunching $multiplyMinDesksByBankSize
        |    - maxDesks should be multiplied by bankSize of 5 when crunching $multiplyMaxDesksByBankSize
    """.stripMargin

  val T2 = "T2"
  val lhrConfigWithSimpleMinMaxDesks = AirportConfigs.lhr
    .copy(minMaxDesksByTerminalQueue =
      Map(T2 -> {
        val mindesks = List.fill(96)(2)
        val maxDesks = List.fill(96)(3)
        Map(Queues.EGate -> (mindesks, maxDesks))
      })
    )

  val wls = Map(T2 -> Map(Queues.EGate -> Seq.fill(60 * 60 * 24)(WL(0, 30))))
  val workloads: Future[TerminalQueuePaxAndWorkLoads[scala.collection.immutable.Seq[WL]]] = Future.successful(wls)

  def multiplyMinDesksByBankSize = {

    val crunchCalculator = new TestableCrunchCalculator {
      override def tryCrunch(terminalName: TerminalName, queueName: String, workloads: List[Double], sla: Int, minDesks: List[Int], maxDesks: List[Int]): Try[OptimizerCrunchResult] = {
        val forAllDesksMultipleOfBankSize = minDesks.forall(x => (x.toDouble % bankSize) == 0)
        assert(forAllDesksMultipleOfBankSize, "minDesks should be a multiple of bankSize")
        Success(OptimizerCrunchResult(Vector.empty[Int], Vector.empty[Int]))
      }
    }
    val cruncRes = crunchCalculator.crunchWorkloads(workloads, T2, Queues.EGate, 1000 * 60 * 60)

    val res = scala.concurrent.Await.result(cruncRes, 2 seconds)
    println(res)
    ok
  }

  def multiplyMaxDesksByBankSize = {

    val crunchCalculator = new TestableCrunchCalculator {
      override def tryCrunch(terminalName: TerminalName, queueName: String, workloads: List[Double], sla: Int, minDesks: List[Int], maxDesks: List[Int]): Try[OptimizerCrunchResult] = {
        val forAllDesksMultipleOfBankSize = maxDesks.forall(x => (x.toDouble % bankSize) == 0)
        assert(forAllDesksMultipleOfBankSize, "maxDesks should be a multiple of bankSize")
        Success(OptimizerCrunchResult(Vector.empty[Int], Vector.empty[Int]))
      }
    }
    val cruncRes = crunchCalculator.crunchWorkloads(workloads, T2, Queues.EGate, 1000 * 60 * 60)

    val res = scala.concurrent.Await.result(cruncRes, 2 seconds)
    println(res)
    ok
  }

  abstract class TestableCrunchCalculator extends LoggingCrunchCalculator {

    override def crunchPeriodHours: Int = 24

    override def log: DiagnosticLoggingAdapter = mock[DiagnosticLoggingAdapter]

    override def airportConfig: AirportConfig = lhrConfigWithSimpleMinMaxDesks


    override protected[actors] def runSimulation(workloads: Seq[Double], recommendedDesks: IndexedSeq[Int], optimizerConfig: OptimizerConfig): SimulationResult =
      SimulationResult(Vector.empty[DeskRec], Nil)
  }

}
