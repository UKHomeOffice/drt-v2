package services.crunch

import java.util.UUID

import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.{StaffMinute, StaffMinutes}
import drt.shared.{MilliDate, StaffMovement}
import services.SDate
import services.graphstages.StaffGraphStage

import scala.concurrent.Await
import scala.concurrent.duration._

object TestableStaffGraphStage {
  def apply(testProbe: TestProbe,
            staffGraphStage: StaffGraphStage
           ): RunnableGraph[(SourceQueueWithComplete[String], SourceQueueWithComplete[String], SourceQueueWithComplete[Seq[StaffMovement]])] = {

    val shiftsSource = Source.queue[String](1, OverflowStrategy.backpressure)
    val fixedPointsSource = Source.queue[String](1, OverflowStrategy.backpressure)
    val movementsSource = Source.queue[Seq[StaffMovement]](1, OverflowStrategy.backpressure)

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(shiftsSource.async, fixedPointsSource.async, movementsSource.async)((_, _, _)) {

      implicit builder =>
        (shifts, fixedPoints, movements) =>
          val staffStage = builder.add(staffGraphStage.async)
          val sink = builder.add(Sink.actorRef(testProbe.ref, "complete"))

          shifts ~> staffStage.in0
          fixedPoints ~> staffStage.in1
          movements ~> staffStage.in2
          staffStage.out ~> sink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}

class StaffGraphStageSpec extends CrunchTestLike {
  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  "Given a staff stage for 1 days " +
    "When I send a shift of 3 minutes with 2 staff" +
    "Then I should see all the 3 staff minutes with 2 staff from shifts" >> {
    val numDays = 1
    val date = "2017-01-01"
    val staffGraphStage = new StaffGraphStage("", None, None, None, () => SDate(date), oneDayMillis, airportConfig.copy(terminalNames = Seq("T1")), numDays)
    val probe = TestProbe("staff")
    val (sh, _, _) = TestableStaffGraphStage(probe, staffGraphStage).run

    Await.ready(sh.offer("shift a,T1,01/01/17,00:00,00:02,2"), 1 second)

    val expected = Set(
      StaffMinute("T1", SDate("2017-01-01T00:00").millisSinceEpoch, 2, 0, 0, None),
      StaffMinute("T1", SDate("2017-01-01T00:01").millisSinceEpoch, 2, 0, 0, None),
      StaffMinute("T1", SDate("2017-01-01T00:02").millisSinceEpoch, 2, 0, 0, None))

    probe.fishForMessage(10 seconds) {
      case StaffMinutes(minutes) =>
        val interestingMinutes = minutes.map(_.copy(lastUpdated = None)).toSet
        println(s"interestingMinute: $interestingMinutes")
        interestingMinutes == expected
    }

    true
  }

  "Given a staff stage for 1 days " +
    "When I send shifts with 2 staff, movements of -1 for lunch and a fixed point of 1 staff" +
    "Then I should see all the minutes affected by the shifts" >> {
    val numDays = 1
    val date = "2017-01-01"
    val staffGraphStage = new StaffGraphStage("", None, None, None, () => SDate(date), oneDayMillis, airportConfig.copy(terminalNames = Seq("T1")), numDays)
    val probe = TestProbe("staff")
    val (sh, fp, mm) = TestableStaffGraphStage(probe, staffGraphStage).run
    val movementUuid = UUID.randomUUID()
    val movements = Seq(
      StaffMovement("T1", "lunch start", MilliDate(SDate(s"${date}T00:00").millisSinceEpoch), -1, movementUuid),
      StaffMovement("T1", "lunch end", MilliDate(SDate(s"${date}T00:01").millisSinceEpoch), 1, movementUuid)
    )

    Await.ready(fp.offer("roving officer a,T1,01/01/17,00:00,00:00,1"), 1 second)
    Await.ready(mm.offer(movements), 1 second)
    Thread.sleep(250L)
    Await.ready(sh.offer("shift a,T1,01/01/17,00:00,00:02,2"), 1 second)

    val expected = Set(
      StaffMinute("T1", SDate("2017-01-01T00:00").millisSinceEpoch, 2, 1, -1, None),
      StaffMinute("T1", SDate("2017-01-01T00:01").millisSinceEpoch, 2, 0, 0, None),
      StaffMinute("T1", SDate("2017-01-01T00:02").millisSinceEpoch, 2, 0, 0, None))

    probe.fishForMessage(10 seconds) {
      case StaffMinutes(minutes) =>
        val interestingMinutes = minutes.map(_.copy(lastUpdated = None)).toSet
        println(s"interestingMinute: $interestingMinutes")
        interestingMinutes == expected
    }

    true
  }

  "Given a staff stage for 2 days, a shift, and a set of movements " +
    "When I send some fixed points " +
    "Then I should see all the minutes affected by the fixed points for 2 days" >> {
    val numDays = 2
    val date = "2017-01-01"
    val staffGraphStage = new StaffGraphStage("", None, None, None, () => SDate(date), oneDayMillis, airportConfig.copy(terminalNames = Seq("T1")), numDays)
    val probe = TestProbe("staff")
    val (_, fp, _) = TestableStaffGraphStage(probe, staffGraphStage).run

    Await.ready(fp.offer("roving officer a,T1,01/01/17,00:00,00:00,1"), 1 second)

    val expected = Set(
      StaffMinute("T1", SDate("2017-01-01T00:00").millisSinceEpoch, 0, 1, 0, None),
      StaffMinute("T1", SDate("2017-01-01T00:00").addDays(1).millisSinceEpoch, 0, 1, 0, None))

    probe.fishForMessage(5 seconds) {
      case StaffMinutes(minutes) => minutes.map(_.copy(lastUpdated = None)).toSet == expected
    }

    true
  }

  "Given a staff stage for 2 days with initial shifts and fixed points " +
    "When I send some movements " +
    "Then I should see all the minutes affected by the movements, and containing the initial data " >> {
    val numDays = 2
    val date = "2017-01-01"
    val initialShifts = "shift a,T1,01/01/17,00:00,00:05,2"
    val initialFixedPoints = "roving officer a,T1,01/01/17,00:00,00:05,1"
    val staffGraphStage = new StaffGraphStage("", Option(initialShifts), Option(initialFixedPoints), None, () => SDate(date), oneDayMillis, airportConfig.copy(terminalNames = Seq("T1")), numDays)
    val probe = TestProbe("staff")
    val (_, _, mm) = TestableStaffGraphStage(probe, staffGraphStage).run

    val movementUuid = UUID.randomUUID()
    val movements = Seq(
      StaffMovement("T1", "lunch start", MilliDate(SDate(s"${date}T00:01").millisSinceEpoch), -1, movementUuid),
      StaffMovement("T1", "lunch end", MilliDate(SDate(s"${date}T00:03").millisSinceEpoch), 1, movementUuid)
    )

    Await.ready(mm.offer(movements), 1 second)

    val expected = Set(
      StaffMinute("T1", SDate("2017-01-01T00:01").millisSinceEpoch, 2, 1, -1, None),
      StaffMinute("T1", SDate("2017-01-01T00:02").millisSinceEpoch, 2, 1, -1, None))

    probe.fishForMessage(5 seconds) {
      case StaffMinutes(minutes) => minutes.map(_.copy(lastUpdated = None)).toSet == expected
    }

    true
  }
}