package services.crunch.staffing

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.{MinutesContainer, StaffMinute}
import drt.shared._
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.time.SDate.implicits.sdateFromMillisLocal
import services.crunch.CrunchTestLike
import services.crunch.deskrecs.RunnableOptimisation.{ProcessingRequest, TerminalUpdateRequest}
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.concurrent.Future

class RunnableStaffingTest extends CrunchTestLike {
  val date: SDateLike = SDate("2022-06-17", Crunch.europeLondonTimeZone)

  val startTime: Long = date.millisSinceEpoch
  val endTime: Long = date.addMinutes(15).millisSinceEpoch

  "Given some mock providers" >> {
    val updateDate = SDate("2022-06-17T12:00:00")
    val probe = TestProbe()

    "When I ask for an update for T1 on 2022-06-17 of 2 minute duration and mocks returning empty values" >> {
      val queue = startStaffingFlow(updateDate, probe, ShiftAssignments.empty, FixedPointAssignments.empty, StaffMovements.empty)
      queue.offer(TerminalUpdateRequest(T1, LocalDate(2022, 6, 17), 0, 2))
      "I should get the 2 consecutive staff minutes for that date starting from midnight with zeros" >> {
        probe.expectMsg(MinutesContainer(Seq(
          StaffMinute(T1, date.millisSinceEpoch, 0, 0, 0, Option(updateDate.millisSinceEpoch)),
          StaffMinute(T1, date.addMinutes(1).millisSinceEpoch, 0, 0, 0, Option(updateDate.millisSinceEpoch)),
        )))

        success
      }
    }

    "When I ask for an update for T1 on 2022-06-17 of 2 minute duration and mocks returning non-empty values" >> {
      val shifts = ShiftAssignments(Seq(StaffAssignment("", T1, startTime, endTime, 1, None)))
      val fixedPoints = FixedPointAssignments(Seq(StaffAssignment("", T1, startTime, endTime, 2, None)))
      val movements = StaffMovements(Seq(
        StaffMovement(T1, "", startTime, -1, "123", None, None),
        StaffMovement(T1, "", endTime, -1, "123", None, None),
      ))

      val queue = startStaffingFlow(updateDate, probe, shifts, fixedPoints, movements)
      queue.offer(TerminalUpdateRequest(T1, LocalDate(2022, 6, 17), 0, 2))

      "I should get the 2 consecutive staff minutes for that date starting from midnight with the values from the mocks" >> {
        probe.expectMsg(MinutesContainer(Seq(
          StaffMinute(T1, date.millisSinceEpoch, 1, 2, -1, Option(updateDate.millisSinceEpoch)),
          StaffMinute(T1, date.addMinutes(1).millisSinceEpoch, 1, 2, -1, Option(updateDate.millisSinceEpoch)),
        )))

        success
      }
    }
  }

   def startStaffingFlow(updateDate: SDateLike,
                         probe: TestProbe,
                         shifts: ShiftAssignments,
                         fixedPoints: FixedPointAssignments,
                         movements: StaffMovements): SourceQueueWithComplete[TerminalUpdateRequest] = {

    val someShifts: ProcessingRequest => Future[ShiftAssignments] = (_: ProcessingRequest) => Future.successful(shifts)
    val someFixedPoints: ProcessingRequest => Future[FixedPointAssignments] = (_: ProcessingRequest) => Future.successful(fixedPoints)
    val someMovements: ProcessingRequest => Future[StaffMovements] = (_: ProcessingRequest) => Future.successful(movements)

    val staffFlow = RunnableStaffing.staffMinutesFlow(someShifts, someFixedPoints, someMovements, () => updateDate)
    val source = Source.queue[TerminalUpdateRequest](1, OverflowStrategy.fail)
    val queue = staffFlow.to(Sink.actorRef(probe.ref, "Done", _ => ())).runWith(source)
    queue
  }
}
