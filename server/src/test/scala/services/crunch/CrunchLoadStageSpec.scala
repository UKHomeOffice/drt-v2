package services.crunch

import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.{DeskRecMinute, DeskRecMinutes}
import drt.shared._
import services.graphstages.Crunch.{LoadMinute, Loads}
import services.graphstages.{Crunch, CrunchLoadGraphStage}
import services.{OptimizerConfig, OptimizerCrunchResult, SDate}

import scala.concurrent.duration._
import scala.util.Try

object TestableCrunchLoadStage {
  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def mockCrunch(wl: Seq[Double], minDesks: Seq[Int], maxDesks: Seq[Int], config: OptimizerConfig): Try[OptimizerCrunchResult] = {
    Try(OptimizerCrunchResult(minDesks.toIndexedSeq, Seq.fill(wl.length)(config.sla)))
  }

  def mockSimulator(workloads: Seq[Double], desks: Seq[Int], config: OptimizerConfig): Seq[Int] = {
    Seq.fill(workloads.length)(config.sla)
  }

  def apply(testProbe: TestProbe, now: () => SDateLike, airportConfig: AirportConfig, minutesToCrunch: Int): RunnableGraph[SourceQueueWithComplete[Loads]] = {
    val crunchLoadStage = new CrunchLoadGraphStage(
      name = "",
      optionalInitialCrunchMinutes = None,
      airportConfig = airportConfig,
      expireAfterMillis = oneDayMillis,
      now = now,
      crunch = mockCrunch,
      crunchPeriodStartMillis = Crunch.getLocalLastMidnight,
      minutesToCrunch = minutesToCrunch)

    val loadSource = Source.queue[Loads](1, OverflowStrategy.backpressure)

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(loadSource.async) {

      implicit builder =>
        (load) =>
          val crunch = builder.add(crunchLoadStage.async)
          val sink = builder.add(Sink.actorRef(testProbe.ref, "complete"))

          load ~> crunch ~> sink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}

class CrunchLoadStageSpec extends CrunchTestLike {
  "Given loads for a set of minutes within a day for 2 queues at one terminal " +
    "When I ask for crunch result " +
    "Then I should see a full day's worth (1440) of crunch minutes per queue crunched - a total of 2880" >> {

    val probe = TestProbe("workload")
    val scheduled = "2018-01-01T00:05"
    val testAirportConfig = airportConfig
    val minutesToCrunch = 1440
    val loadsSource = TestableCrunchLoadStage(probe, () => SDate(scheduled), testAirportConfig, minutesToCrunch).run

    val loads = Loads(Set(
      LoadMinute("T1", Queues.EeaDesk, 10, 5, SDate(scheduled).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 2.5, 1.25, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 10, 10, SDate(scheduled).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 2.5, 2.5, SDate(scheduled).addMinutes(1).millisSinceEpoch)))

    loadsSource.offer(loads)

    val expected = Set(
      DeskRecMinute("T1", Queues.EeaDesk, 1514765100000L, 10.0, 5.0, 1, 25, None),
      DeskRecMinute("T1", Queues.EeaDesk, 1514765160000L, 2.5, 1.25, 1, 25, None),
      DeskRecMinute("T1", Queues.NonEeaDesk, 1514765100000L, 10.0, 10.0, 1, 45, None),
      DeskRecMinute("T1", Queues.NonEeaDesk, 1514765160000L, 2.5, 2.5, 1, 45, None)
    )
    val expectedMillis = loads.loadMinutes.map(_.minute)
    val expectedSize = 2 * minutesToCrunch

    val result = probe.receiveOne(5 seconds) match {
      case DeskRecMinutes(drms) => drms.map(_.copy(lastUpdated = None))
      case unexpected =>
        println(s"Got unexpected: $unexpected")
        Set()
    }

    val interestingMinutes = result.filter(cm => {
      expectedMillis.contains(cm.minute)
    })

    interestingMinutes === expected && result.size === expectedSize
  }

  "Given a loads for a set of minutes within two consecutive days for 2 queues at one terminal " +
    "When I ask for crunch result " +
    "Then I should see 2 full day's worth (2880) of crunch minutes per queue crunched - a total of 5760" >> {

    val probe = TestProbe("workload")
    val scheduledDay1 = "2018-01-01T00:05"
    val scheduledDay2 = "2018-01-02T05:30"
    val testAirportConfig = airportConfig
    val minutesToCrunch = 2880
    val loadsSource = TestableCrunchLoadStage(probe, () => SDate(scheduledDay1), testAirportConfig, minutesToCrunch).run

    val loads = Loads(Set(
      LoadMinute("T1", Queues.EeaDesk, 10, 5, SDate(scheduledDay1).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 2.5, 1.25, SDate(scheduledDay2).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 10, 10, SDate(scheduledDay1).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 2.5, 2.5, SDate(scheduledDay2).millisSinceEpoch)))

    loadsSource.offer(loads)

    val expected = Set(
      DeskRecMinute("T1", Queues.EeaDesk, SDate(scheduledDay1).millisSinceEpoch, 10.0, 5.0, 1, 25, None),
      DeskRecMinute("T1", Queues.EeaDesk, SDate(scheduledDay2).millisSinceEpoch, 2.5, 1.25, 1, 25, None),
      DeskRecMinute("T1", Queues.NonEeaDesk, SDate(scheduledDay1).millisSinceEpoch, 10.0, 10.0, 1, 45, None),
      DeskRecMinute("T1", Queues.NonEeaDesk, SDate(scheduledDay2).millisSinceEpoch, 2.5, 2.5, 1, 45, None)
    )
    val expectedMillis = loads.loadMinutes.map(_.minute)
    val expectedSize = 2 * 2 * 1440

    probe.fishForMessage(5 seconds) {
      case DeskRecMinutes(drms) =>
        val minutes = drms.filter(cm => {
          expectedMillis.contains(cm.minute)
        }).map(_.copy(lastUpdated = None))
        println(s"minutes: $minutes")
        minutes == expected && drms.size == expectedSize
    }

    true
  }
}