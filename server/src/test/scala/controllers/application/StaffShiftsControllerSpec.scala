package controllers.application

import org.specs2.mutable.Specification
import org.specs2.mock.Mockito
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.homeoffice.drt.service.staffing.StaffShiftsPlanService
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import drt.shared.{ShiftAssignments, StaffAssignment, StaffShift}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default._

class StaffShiftsControllerSpec extends Specification with Mockito {

  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  "StaffShiftsController#saveDefaultShift" should {
    "save shifts and update assignments with zero staff" in {
      val mockCtrl = mock[DrtSystemInterface]
      val mockStaffShiftsPlanService = mock[StaffShiftsPlanService]
      val controller = new StaffShiftsController(stubControllerComponents(), mockCtrl, mockStaffShiftsPlanService)

      val shifts = Seq(StaffShift("port", "terminal", "shiftName", LocalDate(2023, 10, 1), "08:00", "16:00", None, 5, None, None, 0L))
      val allShifts = ShiftAssignments(Seq(StaffAssignment("shiftName", Terminal("terminal"), 0L, 0L, 0, None)))

      mockCtrl.staffShiftsService.saveShift(any[Seq[StaffShift]]) returns Future.successful(1)
      mockStaffShiftsPlanService.allShifts returns Future.successful(allShifts)
      mockStaffShiftsPlanService.updateShifts(any[Seq[StaffAssignment]]) returns Future.successful(allShifts)
      implicit val writer: Writer[StaffShift] = macroW[StaffShift]
      val request = FakeRequest().withTextBody(write(shifts))
      val result = controller.saveDefaultShift.apply(request)

      status(result) must beEqualTo(OK)
      contentAsString(result) must contain("Inserted 1 shift(s)")
    }

    "update shifts with zero staff using generateDailyAssignments" in {
      val mockCtrl = mock[DrtSystemInterface]
      val mockStaffShiftsPlanService = mock[StaffShiftsPlanService]
      val controller = new StaffShiftsController(stubControllerComponents(), mockCtrl, mockStaffShiftsPlanService)

      val shifts = Seq(StaffShift("port", "terminal", "shiftName", LocalDate(2023, 10, 1), "08:00", "16:00", None, 5, None, None, 0L))
      val allShifts = ShiftAssignments(Seq(StaffAssignment("shiftName", Terminal("terminal"), 0L, 0L, 0, None)))

      mockCtrl.staffShiftsService.saveShift(any[Seq[StaffShift]]) returns Future.successful(1)
      mockStaffShiftsPlanService.allShifts returns Future.successful(allShifts)
      mockStaffShiftsPlanService.updateShifts(any[Seq[StaffAssignment]]) returns Future.successful(allShifts)

      implicit val writer: Writer[StaffShift] = macroW[StaffShift]
      val request = FakeRequest().withTextBody(write(shifts))
      val result = controller.saveDefaultShift.apply(request)

      status(result) must beEqualTo(OK)
      contentAsString(result) must contain("Inserted 1 shift(s)")

      there was one(mockStaffShiftsPlanService).updateShifts(argThat { assignments: Seq[StaffAssignment] =>
        assignments.exists(_.numberOfStaff == 5)
      })
    }
  }
}