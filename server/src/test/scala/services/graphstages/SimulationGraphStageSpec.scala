package services.graphstages

import actors.DrtStaticParameters
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.TestProbe
import drt.shared.{AirportConfig, Queues}
import drt.shared.CrunchApi._
import services.SDate
import services.crunch.CrunchSystem.crunchStartWithOffset
import services.crunch.CrunchTestLike
import services.graphstages.Crunch.Loads

import scala.concurrent.duration._

object TestableSimulationStage {
  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def apply(testProbe: TestProbe, airportConfig: AirportConfig, initialCrunch: Option[CrunchMinutes], initialStaff: Option[StaffMinutes]): RunnableGraph[(SourceQueueWithComplete[Loads], SourceQueueWithComplete[StaffMinutes])] = {
    val simStage = new SimulationGraphStage(
      name = "",
      optionalInitialCrunchMinutes = initialCrunch,
      optionalInitialStaffMinutes = initialStaff,
      airportConfig = airportConfig,
      expireAfterMillis = DrtStaticParameters.expireAfterMillis,
      now = () => SDate("2018-01-01"),
      simulate = TestableCrunchLoadStage.mockSimulator,
      crunchPeriodStartMillis = crunchStartWithOffset(0),
      minutesToCrunch = 30
    )

    val loadsSource = Source.queue[Loads](1, OverflowStrategy.backpressure)
    val minutesSource = Source.queue[StaffMinutes](1, OverflowStrategy.backpressure)

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(loadsSource.async, minutesSource)((_, _)) {

      implicit builder =>
        (loads, minutes) =>
          val simulation = builder.add(simStage.async)
          val sink = builder.add(Sink.actorRef(testProbe.ref, "complete"))

          loads ~> simulation.in0
          minutes ~> simulation.in1
          simulation.out ~> sink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }

  def staffMinute(terminal: String, minute1: MillisSinceEpoch, shifts: Int = 0, fixedPoints: Int = 0, movements: Int = 0): StaffMinute = {
    StaffMinute(terminal, minute1, shifts, fixedPoints, movements, None)
  }

  def crunchMinute(terminal: String, queue: String, minute1: MillisSinceEpoch): CrunchMinute = {
    CrunchMinute(terminal, queue, minute1, 0, 0, 0, 0, None, None, None, None, None)
  }
}

class SimulationGraphStageSpec extends CrunchTestLike {
  import TestableSimulationStage._

  "something" >> {
    val probe = TestProbe("yeah")

    val minute1 = SDate("2018-01-01T00:00:00").millisSinceEpoch

    val initialCrunch = Option(CrunchMinutes(Set(crunchMinute("T1", Queues.EeaDesk, minute1))))
    val initialStaff = Option(StaffMinutes(Seq(staffMinute("T1", minute1, fixedPoints = 1))))

    val simStage = TestableSimulationStage(probe, airportConfig, initialCrunch, initialStaff)

    val (loadsInput, minutesInput) = simStage.run

    val updatedStaff = StaffMinutes(Seq(staffMinute("T1", minute1, shifts = 10, fixedPoints = 2)))

    minutesInput.offer(updatedStaff)

    probe.receiveWhile(max =10 seconds) {
      case SimulationMinutes(mins) =>
        mins.find {
          case SimulationMinute("T1", Queues.EeaDesk, m, d, w) => true
        }
//        println(s"Got $mins")
      case unexpected => println(s"got unexpected: $unexpected")
    }

    success
  }
}
