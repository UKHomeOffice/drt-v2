package services.graphstages

import java.util.UUID

import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.{StaffMinute, StaffMinutes}
import drt.shared._
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.Await
import scala.concurrent.duration._

object TestableStaffGraphStage {
  def apply(testProbe: TestProbe,
            staffGraphStage: StaffGraphStage
           ): RunnableGraph[(SourceQueueWithComplete[ShiftAssignments], SourceQueueWithComplete[FixedPointAssignments], SourceQueueWithComplete[Seq[StaffMovement]])] = {

    val shiftsSource = Source.queue[ShiftAssignments](1, OverflowStrategy.backpressure)
    val fixedPointsSource = Source.queue[FixedPointAssignments](1, OverflowStrategy.backpressure)
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

  "Given no existing staff movements and one new pair of staff movements " +
    "When I ask for the update criteria " +
    "Then I should see the terminal from the new movements and a start and end time matching the new movement pair" >> {
    val existingMovements = Set[StaffMovement]()
    val movementStart = MilliDate(SDate("2018-01-01T00:05").millisSinceEpoch)
    val movementEnd = MilliDate(SDate("2018-01-01T00:10").millisSinceEpoch)
    val terminal = "T1"
    val uuid = UUID.randomUUID()
    val incomingMovements = Seq(
      StaffMovement(terminal, "", movementStart, -1, uuid, None, createdBy = None),
      StaffMovement(terminal, "", movementEnd, 1, uuid, None, createdBy = None)
    )

    val result = Crunch.movementsUpdateCriteria(existingMovements, incomingMovements)
    val affectedMinuteMillis = movementStart.millisSinceEpoch until movementEnd.millisSinceEpoch by 60000
    val expected = UpdateCriteria(affectedMinuteMillis, Set(terminal))

    result === expected
  }

  "Given a pair of existing staff movements and empty incoming staff movements " +
    "When I ask for the update criteria " +
    "Then I should see the terminal from the existing movements and a start and end time matching the existing pair" >> {
    val movementStart = MilliDate(SDate("2018-01-01T00:05").millisSinceEpoch)
    val movementEnd = MilliDate(SDate("2018-01-01T00:10").millisSinceEpoch)
    val terminal = "T1"
    val uuid = UUID.randomUUID()
    val existingMovements = Set(
      StaffMovement(terminal, "", movementStart, -1, uuid, None, createdBy = None),
      StaffMovement(terminal, "", movementEnd, 1, uuid, None, createdBy = None)
    )
    val incomingMovements = Seq[StaffMovement]()

    val result = Crunch.movementsUpdateCriteria(existingMovements, incomingMovements)
    val affectedMinuteMillis = movementStart.millisSinceEpoch until movementEnd.millisSinceEpoch by 60000
    val expected = UpdateCriteria(affectedMinuteMillis, Set(terminal))

    result === expected
  }

  "Given a staff stage for 1 days " +
    "When I send a shift of 3 minutes with 2 staff " +
    "Then I should see all the 3 staff minutes with 2 staff from shifts" >> {
    val numDays = 1
    val date = "2017-01-01"
    val staffGraphStage = new StaffGraphStage("", ShiftAssignments.empty, FixedPointAssignments.empty, None, () => SDate(date), oneDayMillis, airportConfig.copy(terminalNames = Seq("T1")), numDays)
    val probe = TestProbe("staff")
    val (sh, _, _) = TestableStaffGraphStage(probe, staffGraphStage).run

    val startDate1 = MilliDate(SDate("2017-01-01T00:00").millisSinceEpoch)
    val endDate1 = MilliDate(SDate("2017-01-01T00:02").millisSinceEpoch)
    val assignment1 = StaffAssignment("shift a", "T1", startDate1, endDate1, 2, None)
    Await.ready(sh.offer(ShiftAssignments(Seq(assignment1))), 1 second)

    val expected = Set(
      StaffMinute("T1", SDate("2017-01-01T00:00").millisSinceEpoch, 2, 0, 0, None),
      StaffMinute("T1", SDate("2017-01-01T00:01").millisSinceEpoch, 2, 0, 0, None),
      StaffMinute("T1", SDate("2017-01-01T00:02").millisSinceEpoch, 2, 0, 0, None))

    probe.fishForMessage(10 seconds) {
      case StaffMinutes(minutes) =>
        val interestingMinutes = minutes.map(_.copy(lastUpdated = None)).toSet
        interestingMinutes == expected
    }

    true
  }

  "Given a staff stage for 1 days " +
    "When I send shifts with 2 staff, movements of -1 for lunch and a fixed point of 1 staff " +
    "Then I should see all the minutes affected by the shifts" >> {
    val numDays = 1
    val date = "2017-01-01"
    val staffGraphStage = new StaffGraphStage("", ShiftAssignments.empty, FixedPointAssignments.empty, None, () => SDate(date), oneDayMillis, airportConfig.copy(terminalNames = Seq("T1")), numDays)
    val probe = TestProbe("staff")
    val (sh, fp, mm) = TestableStaffGraphStage(probe, staffGraphStage).run
    val movementUuid = UUID.randomUUID()
    val movements = Seq(
      StaffMovement("T1", "lunch start", MilliDate(SDate(s"${date}T00:00").millisSinceEpoch), -1, movementUuid, createdBy = None),
      StaffMovement("T1", "lunch end", MilliDate(SDate(s"${date}T00:01").millisSinceEpoch), 1, movementUuid, createdBy = None)
    )

    val startDate1 = MilliDate(SDate("2017-01-01T00:00").millisSinceEpoch)
    val endDate1 = MilliDate(SDate("2017-01-01T00:00").millisSinceEpoch)
    val assignment1 = StaffAssignment("shift a", "T1", startDate1, endDate1, 1, None)
    Await.ready(fp.offer(FixedPointAssignments(Seq(assignment1))), 1 second)
    Await.ready(mm.offer(movements), 1 second)
    Thread.sleep(250L)
    val startDate2 = MilliDate(SDate("2017-01-01T00:00").millisSinceEpoch)
    val endDate2 = MilliDate(SDate("2017-01-01T00:02").millisSinceEpoch)
    val assignment2 = StaffAssignment("shift a", "T1", startDate2, endDate2, 2, None)
    Await.ready(sh.offer(ShiftAssignments(Seq(assignment2))), 1 second)

    val expected = Set(
      StaffMinute("T1", SDate("2017-01-01T00:00").millisSinceEpoch, 2, 1, -1, None),
      StaffMinute("T1", SDate("2017-01-01T00:01").millisSinceEpoch, 2, 0, 0, None),
      StaffMinute("T1", SDate("2017-01-01T00:02").millisSinceEpoch, 2, 0, 0, None))

    probe.fishForMessage(10 seconds) {
      case StaffMinutes(minutes) =>
        val interestingMinutes = minutes.map(_.copy(lastUpdated = None)).toSet
        interestingMinutes == expected
    }

    true
  }

  "Given a staff stage for 2 days, a shift, and a set of movements " +
    "When I send some fixed points " +
    "Then I should see all the minutes affected by the fixed points for 2 days" >> {
    val numDays = 2
    val date = "2017-01-01"
    val staffGraphStage = new StaffGraphStage("", ShiftAssignments.empty, FixedPointAssignments.empty, None, () => SDate(date), oneDayMillis, airportConfig.copy(terminalNames = Seq("T1")), numDays)
    val probe = TestProbe("staff")
    val (_, fp, _) = TestableStaffGraphStage(probe, staffGraphStage).run

    val startDate1 = MilliDate(SDate("2017-01-01T00:00").millisSinceEpoch)
    val endDate1 = MilliDate(SDate("2017-01-01T00:00").millisSinceEpoch)
    val assignment1 = StaffAssignment("shift a", "T1", startDate1, endDate1, 1, None)
    Await.ready(fp.offer(FixedPointAssignments(Seq(assignment1))), 1 second)

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
    val startDate1 = MilliDate(SDate("2017-01-01T00:00").millisSinceEpoch)
    val endDate1 = MilliDate(SDate("2017-01-01T00:05").millisSinceEpoch)
    val assignment1 = StaffAssignment("shift a", "T1", startDate1, endDate1, 2, None)
    val initialShifts = ShiftAssignments(Seq(assignment1))
    val startDate2 = MilliDate(SDate("2017-01-01T00:00").millisSinceEpoch)
    val endDate2 = MilliDate(SDate("2017-01-01T00:05").millisSinceEpoch)
    val assignment2 = StaffAssignment("shift a", "T1", startDate2, endDate2, 1, None)
    val initialFixedPoints = FixedPointAssignments(Seq(assignment2))
    val staffGraphStage = new StaffGraphStage("", initialShifts, initialFixedPoints, None, () => SDate(date), oneDayMillis, airportConfig.copy(terminalNames = Seq("T1")), numDays)
    val probe = TestProbe("staff")
    val (_, _, mm) = TestableStaffGraphStage(probe, staffGraphStage).run

    val movementUuid = UUID.randomUUID()
    val movements = Seq(
      StaffMovement("T1", "lunch start", MilliDate(SDate(s"${date}T00:01").millisSinceEpoch), -1, movementUuid, createdBy = None),
      StaffMovement("T1", "lunch end", MilliDate(SDate(s"${date}T00:03").millisSinceEpoch), 1, movementUuid, createdBy = None)
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
