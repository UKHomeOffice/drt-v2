package controllers.application

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{ShiftAssignments, StaffAssignment, StaffAssignmentLike, Shift}

import org.specs2.mutable.Specification
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.Lhr
import uk.gov.homeoffice.drt.service.staffing.StaffAssignmentsService
import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default._

import scala.concurrent.{ExecutionContext, Future}

class ShiftsControllerSpec extends Specification  {

  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  case class MockStaffAssignmentsService(shifts: Seq[StaffAssignmentLike]) extends StaffAssignmentsService {
    override def staffAssignmentsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments] =
      Future.successful(ShiftAssignments(shifts))

    override def allStaffAssignments: Future[ShiftAssignments] =
      Future.successful(ShiftAssignments(shifts))

    override def updateStaffAssignments(shiftAssignments: Seq[StaffAssignmentLike]): Future[ShiftAssignments] = Future.successful(ShiftAssignments(shifts))
  }

  "ShiftsController#saveDefaultShift" should {
    "save shifts and update assignments with zero staff" in {
      val mockCtrl = new TestDrtModule(Lhr.config).provideDrtSystemInterface
      val shifts = Seq(Shift("LHR", "T1", "shiftName", LocalDate(2023, 10, 1), "08:00", "16:00", None, 5, None, None, 0L))
      val allShifts = ShiftAssignments(Seq(StaffAssignment("shiftName", Terminal("T1"), 0L, 0L, 0, None)))

      val mockStaffAssignmentsService = new MockStaffAssignmentsService(allShifts.assignments)
      val controller = new ShiftsController(stubControllerComponents(), mockCtrl, mockStaffAssignmentsService)

      implicit val writer: Writer[Shift] = macroW[Shift]
      val request = FakeRequest().withTextBody(write(shifts)).withHeaders(Headers("X-Forwarded-Groups" -> "staff:edit,LHR"))
      val result = controller.saveShifts.apply(request)

      val expectResult =
      """{"indexedAssignments":[[{"terminal":"uk.gov.homeoffice.drt.ports.Terminals.T1","minute":0},{"$type":"drt.shared.StaffAssignment","name":"shiftName","terminal":"uk.gov.homeoffice.drt.ports.Terminals.T1","start":0,"end":0,"numberOfStaff":0,"createdBy":[]}]]}"""
      status(result) must beEqualTo(OK)
      contentAsString(result) must contain(expectResult)
    }

    "update shifts with zero staff using generateDailyAssignments" in {
      val mockCtrl = new TestDrtModule(Lhr.config).provideDrtSystemInterface
      val allShifts = ShiftAssignments(Seq(StaffAssignment("shiftName", Terminal("T1"), 0L, 0L, 0, None)))
      val mockStaffShiftsPlanService = new MockStaffAssignmentsService(allShifts.assignments)

      val controller = new ShiftsController(stubControllerComponents(), mockCtrl, mockStaffShiftsPlanService)

      val request = FakeRequest().withTextBody(write(allShifts)).withHeaders(Headers("X-Forwarded-Groups" -> "staff:edit,LHR"))
      val result = controller.saveStaffAssignments.apply(request)

      val expectResult = """{"indexedAssignments":[[{"terminal":"uk.gov.homeoffice.drt.ports.Terminals.T1","minute":0},{"$type":"drt.shared.StaffAssignment","name":"shiftName","terminal":"uk.gov.homeoffice.drt.ports.Terminals.T1","start":0,"end":0,"numberOfStaff":0,"createdBy":[]}]]}"""
      status(result) must beEqualTo(ACCEPTED)
      contentAsString(result) must contain(expectResult)

    }
  }
}