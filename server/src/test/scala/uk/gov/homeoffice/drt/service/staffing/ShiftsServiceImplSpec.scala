package uk.gov.homeoffice.drt.service.staffing

import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.persistent.staffing.LegacyShiftAssignmentsActor.UpdateShifts
import org.apache.pekko.Done
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.Timeout
import drt.shared.{ShiftAssignments, StaffAssignment}
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar.mock
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.db.dao.StaffShiftsDao
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration.DurationInt


class ShiftsServiceImplSpec extends TestKit(ActorSystem("test")) with AnyWordSpecLike with Matchers {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(1.second)

  def mockActor(probe: ActorRef): ActorRef = system.actorOf(Props(new MockActor(probe)))

  val liveProbe: TestProbe = TestProbe("live")
  val writeProbe: TestProbe = TestProbe("write")
  val pitProbe: TestProbe = TestProbe("pit")
  private val systemTime = System.currentTimeMillis()
  private val shift = Shift(port = "LHR",
    terminal = "T2",
    shiftName = "TEST1",
    startDate = LocalDate(2024, 7, 1),
    startTime = "09:00",
    endTime = "15:00",
    endDate = None,
    staffNumber = 5,
    frequency = None,
    createdBy = None,
    createdAt = systemTime)


  "getActiveShifts" should {
    val service = LegacyShiftAssignmentsServiceImpl(mockActor(liveProbe.ref), mockActor(writeProbe.ref), _ => mockActor(pitProbe.ref))
    val assignments = ShiftAssignments(Seq(
      StaffAssignment("assignment", T1, SDate("2024-07-01T05:00").millisSinceEpoch, SDate("2024-07-01T12:00").millisSinceEpoch, 1, None)))
    "return a list of staff assignments for a given date" in {
      MockActor.response = assignments
      val result = service.shiftAssignmentsForDate(LocalDate(2024, 7, 1), None)
      result.futureValue should ===(assignments)
      liveProbe.expectMsgClass(classOf[GetStateForDateRange])
    }

    "return a list of staff assignments for a given point in time" in {
      MockActor.response = assignments
      val result = service.shiftAssignmentsForDate(LocalDate(2024, 7, 1), Some(SDate("2024-07-01T05:00").millisSinceEpoch))
      result.futureValue should ===(assignments)
      pitProbe.expectMsgClass(classOf[GetStateForDateRange])
    }

    "return a list of staff assignments for a month" in {
      MockActor.response = assignments
      val result = service.allShiftAssignments
      result.futureValue.getClass should ===(classOf[ShiftAssignments])
      liveProbe.expectMsg(GetState)
    }

    "update shifts" in {
      MockActor.response = Done
      service.updateShiftAssignments(assignments.assignments)
      writeProbe.expectMsg(UpdateShifts(assignments.assignments))
    }

    "filter active shifts by date" in {
      val mockDao = mock[StaffShiftsDao]
      val service = ShiftsServiceImpl(mockDao)
      val testShifts: Seq[Shift] = Seq(
        shift.copy(startDate = LocalDate(2024, 7, 1), endDate = Option(LocalDate(2024, 7, 30)), shiftName = "TEST1", startTime = "09:00", endTime = "15:00"),
        shift.copy(startDate = LocalDate(2024, 8, 1), shiftName = "TEST2", startTime = "14:00", endTime = "18:00"),
        shift.copy(startDate = LocalDate(2024, 9, 1), shiftName = "TEST3", startTime = "17:00", endTime = "23:00")
      )

      when(mockDao.getStaffShiftsByPortAndTerminal("LHR", "T2"))
        .thenReturn(Future.successful(testShifts))

      val result = Await.result(service.getActiveShifts("LHR", "T2", Some("2024-08-02")), 1.seconds)
      val expectedShift = testShifts.filter(s => s.shiftName == "TEST2").sortBy(_.startDate)
      result should contain theSameElementsAs expectedShift
    }
  }

  "getActiveShiftsForViewRange" should {

    "filter shifts for daily view" in {
      val mockDao = mock[StaffShiftsDao]
      val service = ShiftsServiceImpl(mockDao)
      val shift1 = shift.copy(startDate = LocalDate(2024, 6, 1), endDate = Option(LocalDate(2024, 6, 10)),
        shiftName = "TEST1", startTime = "09:00", endTime = "15:00")
      val shift2 = shift.copy(startDate = LocalDate(2024, 5, 25), endDate = Option(LocalDate(2024, 6, 4)),
        shiftName = "TEST2", startTime = "14:00", endTime = "18:00")
      val shifts = Seq(shift1, shift2)

      when(mockDao.getStaffShiftsByPortAndTerminal("LHR", "T2")).thenReturn(Future.successful(shifts))

      val result = Await.result(service.getActiveShiftsForViewRange("LHR", "T2", Some("daily"), Some("2024-06-05")), 1.seconds)
      result should contain allElementsOf Seq(shift1)

    }

    "filter shifts for weekly view" in {
      val mockDao = mock[StaffShiftsDao]
      val service = ShiftsServiceImpl(mockDao)
      val shift1 = shift.copy(startDate = LocalDate(2024, 6, 1),
        endDate = Option(LocalDate(2024, 6, 10)), shiftName = "TEST1", startTime = "09:00", endTime = "15:00")
      val shift2 = shift.copy(startDate = LocalDate(2024, 5, 25),
        endDate = Option(LocalDate(2024, 6, 5)), shiftName = "TEST2", startTime = "14:00", endTime = "18:00")
      val shift3 = shift.copy(startDate = LocalDate(2024, 6, 15),
        endDate = Option(LocalDate(2024, 6, 20)), shiftName = "TEST3", startTime = "10:00", endTime = "16:00") // Should be filtered out

      val shifts = Seq(shift1, shift2, shift3)

      when(mockDao.getStaffShiftsByPortAndTerminal("LHR", "T2")).thenReturn(Future.successful(shifts))

      val result = Await.result(service.getActiveShiftsForViewRange("LHR", "T2", Some("weekly"), Some("2024-06-05")), 1.seconds)

      result should contain allElementsOf Seq(shift1, shift2)
      result should not contain shift3

    }

    "filter shifts that has start of week for viewDate not before endDate of shift" in {
      val mockDao = mock[StaffShiftsDao]
      val service = ShiftsServiceImpl(mockDao)
      val endDateBeforeStartOfWeek = LocalDate(2024, 6, 2) // week starts on 3rd June
      val shift1 = shift.copy(startDate = LocalDate(2024, 6, 1),
        endDate = Option(LocalDate(2024, 6, 10)), shiftName = "TEST1", startTime = "09:00", endTime = "15:00")
      val shift2 = shift.copy(startDate = LocalDate(2024, 5, 25),
        endDate = Option(endDateBeforeStartOfWeek), shiftName = "TEST2", startTime = "14:00", endTime = "18:00")
      val shifts = Seq(shift1, shift2)

      when(mockDao.getStaffShiftsByPortAndTerminal("LHR", "T2")).thenReturn(Future.successful(shifts))

      val result = Await.result(service.getActiveShiftsForViewRange("LHR", "T2", Some("weekly"), Some("2024-06-05")), 1.seconds)
      result should contain allElementsOf Seq(shift1)

    }

    "filter shifts for month view" in {
      val mockDao = mock[StaffShiftsDao]
      val service = ShiftsServiceImpl(mockDao)
      val shift1 = shift.copy(startDate = LocalDate(2024, 6, 1),
        endDate = Option(LocalDate(2024, 6, 10)), shiftName = "TEST1", startTime = "09:00", endTime = "15:00")
      val shift2 = shift.copy(startDate = LocalDate(2024, 5, 25),
        endDate = Option(LocalDate(2024, 6, 5)), shiftName = "TEST2", startTime = "14:00", endTime = "18:00")
      val shift3 = shift.copy(startDate = LocalDate(2024, 7, 1),
        endDate = Option(LocalDate(2024, 7, 10)), shiftName = "TEST3", startTime = "10:00", endTime = "16:00")
      val shifts = Seq(shift1, shift2, shift3)

      when(mockDao.getStaffShiftsByPortAndTerminal("LHR", "T2")).thenReturn(Future.successful(shifts))

      val result = Await.result(service.getActiveShiftsForViewRange("LHR", "T2", None, Some("2024-06-05")), 1.seconds)
      result should contain allElementsOf Seq(shift1, shift2)
    }


  }

}
