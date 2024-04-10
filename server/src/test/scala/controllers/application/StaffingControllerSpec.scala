package controllers.application

import akka.Done
import akka.actor.ActorSystem
import akka.stream.Materializer
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import module.DRTModule
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.play.PlaySpec
import play.api.mvc.{AnyContentAsEmpty, AnyContentAsText, Headers}
import play.api.test.Helpers._
import play.api.test._
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.config.Lhr
import uk.gov.homeoffice.drt.service.staffing.{FixedPointsService, ShiftsService, StaffMovementsService}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}
import upickle.default.write

import scala.concurrent.Future

case class MockShiftsService(shifts: Seq[StaffAssignmentLike]) extends ShiftsService {
  override def shiftsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments] =
    Future.successful(ShiftAssignments(shifts))

  override def shiftsForMonth(month: MillisSinceEpoch): Future[MonthOfShifts] =
    Future.successful(MonthOfShifts(month, ShiftAssignments(shifts)))

  override def updateShifts(shiftAssignments: Seq[StaffAssignmentLike]): Unit = ()
}

case class MockFixedPointsService(fixedPoints: Seq[StaffAssignmentLike]) extends FixedPointsService {
  override def fixedPoints(maybePointInTime: Option[MillisSinceEpoch]): Future[FixedPointAssignments] =
    Future.successful(FixedPointAssignments(fixedPoints))

  override def updateFixedPoints(shiftAssignments: Seq[StaffAssignmentLike]): Unit = ()
}

case class MockStaffMovementsService(movements: Seq[StaffMovement]) extends StaffMovementsService {
  override def movementsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[Seq[StaffMovement]] =
    Future.successful(movements)

  override def addMovements(movements: List[StaffMovement]): Future[Done.type] = Future.successful(Done)

  override def removeMovements(movementUuid: String): Unit = ()
}

class StaffingControllerSpec extends PlaySpec with BeforeAndAfterEach {
  implicit val system: ActorSystem = akka.actor.ActorSystem("test")
  implicit val mat: Materializer = Materializer(system)


  val now: () => SDateLike = () => SDate("2024-06-26T12:00")

  val shifts: Seq[StaffAssignmentLike] =
    Seq(StaffAssignment("assignment", T1, SDate("2024-07-01T05:00").millisSinceEpoch, SDate("2024-07-01T12:00").millisSinceEpoch, 1, None))

  val fixedPoints: Seq[StaffAssignmentLike] =
    Seq(StaffAssignment("assignment", T1, SDate("2024-07-01T05:00").millisSinceEpoch, SDate("2024-07-01T12:00").millisSinceEpoch, 1, None))

  val movements: Seq[StaffMovement] = Seq(StaffMovement(T1, "some reason", SDate("2024-07-01T05:00").millisSinceEpoch, 1, "abc", None, None))

  val controller: StaffingController = newController(newDrtInterface())

  "getShifts" should {
    "return the shifts from the mock service as json" in {
      val authHeader = Headers("X-Auth-Roles" -> "fixed-points:view,LHR")
      val result = controller
        .getShifts("2024-06-26")
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)
      contentAsString(result) must ===(write(ShiftAssignments(shifts)))
    }
  }

  "saveShifts" should {
    "return Accepted" in {
      val authHeader = Headers("X-Auth-Roles" -> "staff:edit,LHR")
      val result = controller
        .saveShifts
        .apply(FakeRequest(method = "POST", uri = "", headers = authHeader, body = AnyContentAsText(write(ShiftAssignments(shifts)))))

      status(result) must ===(ACCEPTED)
    }
    "return BadRequest" in {
      val authHeader = Headers("X-Auth-Roles" -> "staff:edit,LHR")
      val result = controller
        .saveShifts
        .apply(FakeRequest(method = "POST", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(BAD_REQUEST)
    }
  }

  "getShiftsForMonth" should {
    "return the shifts from the mock service as json" in {
      val authHeader = Headers("X-Auth-Roles" -> "staff:edit,LHR")
      val result = controller
        .getShiftsForMonth(SDate("2024-07-01T01:00").millisSinceEpoch)
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)
      contentAsString(result) must ===(write(MonthOfShifts(SDate("2024-07-01T01:00").millisSinceEpoch, ShiftAssignments(shifts))))
    }
  }

  "getFixedPoints" should {
    "return the fixed points from the mock service as json" in {
      val authHeader = Headers("X-Auth-Roles" -> "fixed-points:view,LHR")
      val result = controller
        .getFixedPoints
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)
      contentAsString(result) must ===(write(FixedPointAssignments(fixedPoints)))
    }
  }

  "saveFixedPoints" should {
    "return Accepted" in {
      val authHeader = Headers("X-Auth-Roles" -> "fixed-points:edit,LHR")
      val result = controller
        .saveFixedPoints
        .apply(FakeRequest(method = "POST", uri = "", headers = authHeader, body = AnyContentAsText(write(FixedPointAssignments(fixedPoints)))))

      status(result) must ===(ACCEPTED)
    }
    "return BadRequest" in {
      val authHeader = Headers("X-Auth-Roles" -> "fixed-points:edit,LHR")
      val result = controller
        .saveFixedPoints
        .apply(FakeRequest(method = "POST", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(BAD_REQUEST)
    }
  }

  "addStaffMovements" should {
    "return Accepted" in {
      val authHeader = Headers("X-Auth-Roles" -> "staff-movements:edit,LHR")
      val result = controller
        .addStaffMovements
        .apply(FakeRequest(method = "POST", uri = "", headers = authHeader, body = AnyContentAsText(write(movements))))

      status(result) must ===(ACCEPTED)
    }
    "return BadRequest" in {
      val authHeader = Headers("X-Auth-Roles" -> "staff-movements:edit,LHR")
      val result = controller
        .addStaffMovements
        .apply(FakeRequest(method = "POST", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(BAD_REQUEST)
    }
  }

  "removeStaffMovements" should {
    "return Accepted" in {
      val authHeader = Headers("X-Auth-Roles" -> "staff-movements:edit,LHR")
      val result = controller
        .removeStaffMovements("abc")
        .apply(FakeRequest(method = "DELETE", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(ACCEPTED)
    }
  }

  "getStaffMovements" should {
    "return the movements from the mock service as json" in {
      val authHeader = Headers("X-Auth-Roles" -> "border-force-staff,LHR")
      val result = controller
        .getStaffMovements("2024-06-26")
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)
      contentAsString(result) must ===(write(movements))
    }
  }

  "exportStaffMovements" should {
    "return the movements from the mock service in csv format" in {
      val authHeader = Headers("X-Auth-Roles" -> "staff-movements:export,LHR")
      val result = controller
        .exportStaffMovements("T1", "2024-06-26")
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)
      contentAsString(result) must ===("""Terminal,Reason,Time,Staff Change,Made by
                                         |T1,some reason,2024-07-01 06:00,1,""".stripMargin)
    }
  }

  "when called without necessary role header, the endpoints" should {
    "return Forbidden for getShifts" in {
      val result = controller
        .getShifts("2024-06-26")
        .apply(FakeRequest(method = "GET", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for saveShifts" in {
      val result = controller
        .saveShifts
        .apply(FakeRequest(method = "POST", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for getShiftsForMonth" in {
      val result = controller
        .getShiftsForMonth(SDate("2024-07-01T01:00").millisSinceEpoch)
        .apply(FakeRequest(method = "GET", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for getFixedPoints" in {
      val result = controller
        .getFixedPoints
        .apply(FakeRequest(method = "GET", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for saveFixedPoints" in {
      val result = controller
        .saveFixedPoints
        .apply(FakeRequest(method = "POST", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for addStaffMovements" in {
      val result = controller
        .addStaffMovements
        .apply(FakeRequest(method = "POST", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for removeStaffMovements" in {
      val result = controller
        .removeStaffMovements("abc")
        .apply(FakeRequest(method = "DELETE", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for getStaffMovements" in {
      val result = controller
        .getStaffMovements("2024-06-26")
        .apply(FakeRequest(method = "GET", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for exportStaffMovements" in {
      val result = controller
        .exportStaffMovements("T1", "2024-06-26")
        .apply(FakeRequest(method = "GET", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
  }

  private def newController(interface: DrtSystemInterface) =
    new StaffingController(
      Helpers.stubControllerComponents(),
      interface,
      MockShiftsService(shifts),
      MockFixedPointsService(fixedPoints),
      MockStaffMovementsService(movements)
    )

  private def newDrtInterface(): DrtSystemInterface = {
    val mod = new DRTModule() {
      override val isTestEnvironment: Boolean = true
      override val airportConfig: AirportConfig = Lhr.config
    }
    mod.provideDrtSystemInterface
  }
}
