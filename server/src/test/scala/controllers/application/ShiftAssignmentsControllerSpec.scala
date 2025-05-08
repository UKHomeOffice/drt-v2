package controllers.application

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Shift, ShiftAssignments, StaffAssignment, StaffAssignmentLike}
import org.specs2.mutable.Specification
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.config.Lhr
import uk.gov.homeoffice.drt.service.staffing.ShiftAssignmentsService
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}
import upickle.default._

import scala.concurrent.{ExecutionContext, Future}

class ShiftAssignmentsControllerSpec extends Specification  {

  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  case class MockShiftAssignmentsService(shifts: Seq[StaffAssignmentLike]) extends ShiftAssignmentsService {
    override def shiftAssignmentsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments] =
      Future.successful(ShiftAssignments(shifts))

    override def allShiftAssignments: Future[ShiftAssignments] =
      Future.successful(ShiftAssignments(shifts))

    override def updateShiftAssignments(shiftAssignments: Seq[StaffAssignmentLike]): Future[ShiftAssignments] = Future.successful(ShiftAssignments(shifts))
  }

  "ShiftsController#saveDefaultShift" should {
    val allShifts = ShiftAssignments(Seq(StaffAssignment("shiftName", Terminal("T1"), 0L, 0L, 0, None)))
    val mockShiftAssignmentsService = MockShiftAssignmentsService(allShifts.assignments)

    val mockCtrl = new TestDrtModule(Lhr.config).provideDrtSystemInterface

    val shiftAssignmentsController = new ShiftAssignmentsController(stubControllerComponents(), mockCtrl, mockShiftAssignmentsService)

    "save shifts and update assignments with zero staff" in {
      val mockCtrl = new TestDrtModule(Lhr.config).provideDrtSystemInterface
      val shifts = Seq(Shift("LHR", "T1", "shiftName", LocalDate(2023, 10, 1), "08:00", "16:00", None, 5, None, None, 0L))

      val shiftsController = new ShiftsController(stubControllerComponents(), mockCtrl, mockShiftAssignmentsService)

      implicit val writer: Writer[Shift] = macroW[Shift]
      val request = FakeRequest().withTextBody(write(shifts)).withHeaders(Headers("X-Forwarded-Groups" -> "staff:edit,LHR"))
      val result = shiftsController.saveShifts.apply(request)

      val expectResult =
      """{"indexedAssignments":[[{"terminal":"uk.gov.homeoffice.drt.ports.Terminals.T1","minute":0},{"$type":"drt.shared.StaffAssignment","name":"shiftName","terminal":"uk.gov.homeoffice.drt.ports.Terminals.T1","start":0,"end":0,"numberOfStaff":0,"createdBy":[]}]]}"""
      status(result) must beEqualTo(OK)
      contentAsString(result) must contain(expectResult)
    }

    "update shifts with zero staff using generateDailyAssignments" in {
      val request = FakeRequest().withTextBody(write(allShifts)).withHeaders(Headers("X-Forwarded-Groups" -> "staff:edit,LHR"))
      val result = shiftAssignmentsController.saveShiftAssignments.apply(request)

      val expectResult = """{"indexedAssignments":[[{"terminal":"uk.gov.homeoffice.drt.ports.Terminals.T1","minute":0},{"$type":"drt.shared.StaffAssignment","name":"shiftName","terminal":"uk.gov.homeoffice.drt.ports.Terminals.T1","start":0,"end":0,"numberOfStaff":0,"createdBy":[]}]]}"""
      status(result) must beEqualTo(ACCEPTED)
      contentAsString(result) must contain(expectResult)

    }
    "return Forbidden for getShiftAssignmentsForDate" in {
      val result = shiftAssignmentsController
        .getShiftAssignmentsForDate("2024-06-26")
        .apply(FakeRequest(method = "GET", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for saveShifts" in {
      val result = shiftAssignmentsController
        .saveShiftAssignments
        .apply(FakeRequest(method = "POST", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for getShiftAssignmentsForDateForMonth" in {
      val result = shiftAssignmentsController
        .getAllShiftAssignments
        .apply(FakeRequest(method = "GET", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }

    val shifts: Seq[StaffAssignmentLike] =
      Seq(StaffAssignment("assignment", T1, SDate("2024-07-01T05:00").millisSinceEpoch, SDate("2024-07-01T12:00").millisSinceEpoch, 1, None))

    "getShiftAssignmentsForDate" should {
      val mockShiftAssignmentsService = MockShiftAssignmentsService(shifts)
      val shiftAssignmentsController = new ShiftAssignmentsController(stubControllerComponents(), mockCtrl, mockShiftAssignmentsService)
      "return the shifts from the mock service as json" in {
        val authHeader = Headers("X-Forwarded-Groups" -> "fixed-points:view,LHR")
        val result = shiftAssignmentsController
          .getShiftAssignmentsForDate("2024-06-26")
          .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

        status(result) must ===(OK)
        contentAsString(result) must ===(write(ShiftAssignments(shifts)))
      }
    }

    "saveShifts" should {
      "return Accepted" in {
        val authHeader = Headers("X-Forwarded-Groups" -> "staff:edit,LHR")
        val result = shiftAssignmentsController
          .saveShiftAssignments
          .apply(FakeRequest(method = "POST", uri = "", headers = authHeader, body = AnyContentAsText(write(ShiftAssignments(shifts)))))

        status(result) must ===(ACCEPTED)
      }
      "return BadRequest" in {
        val authHeader = Headers("X-Forwarded-Groups" -> "staff:edit,LHR")
        val result = shiftAssignmentsController
          .saveShiftAssignments
          .apply(FakeRequest(method = "POST", uri = "", headers = authHeader, body = AnyContentAsEmpty))

        status(result) must ===(BAD_REQUEST)
      }
    }

    "getShiftAssignmentsForDateForMonth" should {
      "return the shifts from the mock service as json" in {
        val mockShiftAssignmentsService = MockShiftAssignmentsService(shifts)
        val shiftAssignmentsController = new ShiftAssignmentsController(stubControllerComponents(), mockCtrl, mockShiftAssignmentsService)
        val authHeader = Headers("X-Forwarded-Groups" -> "staff:edit,LHR")
        val result = shiftAssignmentsController
          .getAllShiftAssignments
          .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

        status(result) must ===(OK)
        contentAsString(result) must ===(write(ShiftAssignments(shifts)))
      }
    }
  }

}
