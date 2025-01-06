package controllers.application

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{ShiftAssignments, StaffAssignment, StaffAssignmentLike, StaffShift}

import org.specs2.mutable.Specification
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.Lhr
import uk.gov.homeoffice.drt.service.staffing.StaffShiftsPlanService
import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default._

import scala.concurrent.{ExecutionContext, Future}

class StaffShiftsControllerSpec extends Specification  {

  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  case class MockStaffShiftsPlanService(shifts: Seq[StaffAssignmentLike]) extends StaffShiftsPlanService {
    override def shiftsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments] =
      Future.successful(ShiftAssignments(shifts))

    override def allShifts: Future[ShiftAssignments] =
      Future.successful(ShiftAssignments(shifts))

    override def updateShifts(shiftAssignments: Seq[StaffAssignmentLike]): Future[ShiftAssignments] = Future.successful(ShiftAssignments(shifts))
  }

  "StaffShiftsController#saveDefaultShift" should {
    "save shifts and update assignments with zero staff" in {
      val mockCtrl = new TestDrtModule(Lhr.config).provideDrtSystemInterface
      val shifts = Seq(StaffShift("LHR", "T1", "shiftName", LocalDate(2023, 10, 1), "08:00", "16:00", None, 5, None, None, 0L))
      val allShifts = ShiftAssignments(Seq(StaffAssignment("shiftName", Terminal("T1"), 0L, 0L, 0, None)))

      val mockStaffShiftsPlanService = new MockStaffShiftsPlanService(allShifts.assignments)
      val controller = new StaffShiftsController(stubControllerComponents(), mockCtrl, mockStaffShiftsPlanService)

      implicit val writer: Writer[StaffShift] = macroW[StaffShift]
      val request = FakeRequest().withTextBody(write(shifts)).withHeaders(Headers("X-Forwarded-Groups" -> "staff:edit,LHR"))
      val result = controller.saveDefaultShift.apply(request)

      status(result) must beEqualTo(OK)
      contentAsString(result) must contain("Inserted 1 shift(s)")
    }

    "update shifts with zero staff using generateDailyAssignments" in {
      val mockCtrl = new TestDrtModule(Lhr.config).provideDrtSystemInterface
      val allShifts = ShiftAssignments(Seq(StaffAssignment("shiftName", Terminal("T1"), 0L, 0L, 0, None)))
      val mockStaffShiftsPlanService = new MockStaffShiftsPlanService(allShifts.assignments)

      val controller = new StaffShiftsController(stubControllerComponents(), mockCtrl, mockStaffShiftsPlanService)

      val request = FakeRequest().withTextBody(write(allShifts)).withHeaders(Headers("X-Forwarded-Groups" -> "staff:edit,LHR"))
      val result = controller.saveShifts.apply(request)

      val expectResult = """{"indexedAssignments":[[{"terminal":"uk.gov.homeoffice.drt.ports.Terminals.T1","minute":0},{"$type":"drt.shared.StaffAssignment","name":"shiftName","terminal":"uk.gov.homeoffice.drt.ports.Terminals.T1","start":0,"end":0,"numberOfStaff":0,"createdBy":[]}]]}"""
      status(result) must beEqualTo(ACCEPTED)
      contentAsString(result) must contain(expectResult)

    }
  }
}