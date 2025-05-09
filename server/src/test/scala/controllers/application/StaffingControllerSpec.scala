package controllers.application

import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.play.PlaySpec
import play.api.mvc.{AnyContentAsEmpty, AnyContentAsText, Headers}
import play.api.test.Helpers._
import play.api.test._
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.config.Lhr
import uk.gov.homeoffice.drt.service.staffing.{FixedPointsService, LegacyShiftAssignmentsService, StaffMovementsService}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}
import upickle.default.write

import scala.concurrent.Future

case class MockLegacyShiftAssignmentsService(shifts: Seq[StaffAssignmentLike]) extends LegacyShiftAssignmentsService {
  override def shiftAssignmentsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments] =
    Future.successful(ShiftAssignments(shifts))

  override def allShiftAssignments: Future[ShiftAssignments] =
    Future.successful(ShiftAssignments(shifts))

  override def updateShiftAssignments(shiftAssignments: Seq[StaffAssignmentLike]): Future[ShiftAssignments] = Future.successful(ShiftAssignments(shifts))
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
  implicit val system: ActorSystem = ActorSystem("test")
  implicit val mat: Materializer = Materializer(system)


  val now: () => SDateLike = () => SDate("2024-06-26T12:00")

  val fixedPoints: Seq[StaffAssignmentLike] =
    Seq(StaffAssignment("assignment", T1, SDate("2024-07-01T05:00").millisSinceEpoch, SDate("2024-07-01T12:00").millisSinceEpoch, 1, None))

  val movements: Seq[StaffMovement] = Seq(StaffMovement(T1, "some reason", SDate("2024-07-01T05:00").millisSinceEpoch, 1, "abc", None, None))

  val staffingController: StaffingController = newStaffingController(newDrtInterface())

  "getFixedPoints" should {
    "return the fixed points from the mock service as json" in {
      val authHeader = Headers("X-Forwarded-Groups" -> "fixed-points:view,LHR")
      val result = staffingController
        .getFixedPoints
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)
      contentAsString(result) must ===(write(FixedPointAssignments(fixedPoints)))
    }
  }

  "saveFixedPoints" should {
    "return Accepted" in {
      val authHeader = Headers("X-Forwarded-Groups" -> "fixed-points:edit,LHR")
      val result = staffingController
        .saveFixedPoints
        .apply(FakeRequest(method = "POST", uri = "", headers = authHeader, body = AnyContentAsText(write(FixedPointAssignments(fixedPoints)))))

      status(result) must ===(ACCEPTED)
    }
    "return BadRequest" in {
      val authHeader = Headers("X-Forwarded-Groups" -> "fixed-points:edit,LHR")
      val result = staffingController
        .saveFixedPoints
        .apply(FakeRequest(method = "POST", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(BAD_REQUEST)
    }
  }

  "addStaffMovements" should {
    "return Accepted" in {
      val authHeader = Headers("X-Forwarded-Groups" -> "staff-movements:edit,LHR")
      val result = staffingController
        .addStaffMovements
        .apply(FakeRequest(method = "POST", uri = "", headers = authHeader, body = AnyContentAsText(write(movements))))

      status(result) must ===(ACCEPTED)
    }
    "return BadRequest" in {
      val authHeader = Headers("X-Forwarded-Groups" -> "staff-movements:edit,LHR")
      val result = staffingController
        .addStaffMovements
        .apply(FakeRequest(method = "POST", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(BAD_REQUEST)
    }
  }

  "removeStaffMovements" should {
    "return Accepted" in {
      val authHeader = Headers("X-Forwarded-Groups" -> "staff-movements:edit,LHR")
      val result = staffingController
        .removeStaffMovements("abc")
        .apply(FakeRequest(method = "DELETE", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(ACCEPTED)
    }
  }

  "getStaffMovements" should {
    "return the movements from the mock service as json" in {
      val authHeader = Headers("X-Forwarded-Groups" -> "border-force-staff,LHR")
      val result = staffingController
        .getStaffMovements("2024-06-26")
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)
      contentAsString(result) must ===(write(movements))
    }
  }

  "exportStaffMovements" should {
    "return the movements from the mock service in csv format" in {
      val authHeader = Headers("X-Forwarded-Groups" -> "staff-movements:export,LHR")
      val result = staffingController
        .exportStaffMovements("T1", "2024-06-26")
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)
      contentAsString(result) must ===(
        """Terminal,Reason,Time,Staff Change,Made by
          |T1,some reason,2024-07-01 06:00,1,""".stripMargin)
    }
  }

  "when called without necessary role header, the endpoints" should {
    "return Forbidden for getFixedPoints" in {
      val result = staffingController
        .getFixedPoints
        .apply(FakeRequest(method = "GET", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for saveFixedPoints" in {
      val result = staffingController
        .saveFixedPoints
        .apply(FakeRequest(method = "POST", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for addStaffMovements" in {
      val result = staffingController
        .addStaffMovements
        .apply(FakeRequest(method = "POST", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for removeStaffMovements" in {
      val result = staffingController
        .removeStaffMovements("abc")
        .apply(FakeRequest(method = "DELETE", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for getStaffMovements" in {
      val result = staffingController
        .getStaffMovements("2024-06-26")
        .apply(FakeRequest(method = "GET", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
    "return Forbidden for exportStaffMovements" in {
      val result = staffingController
        .exportStaffMovements("T1", "2024-06-26")
        .apply(FakeRequest(method = "GET", uri = "", headers = Headers(), body = AnyContentAsEmpty))

      status(result) must ===(FORBIDDEN)
    }
  }

  private def newStaffingController(interface: DrtSystemInterface) =
    new StaffingController(
      Helpers.stubControllerComponents(),
      interface,
      MockFixedPointsService(fixedPoints),
      MockStaffMovementsService(movements),
    )

  private def newDrtInterface(): DrtSystemInterface = {
    new TestDrtModule(Lhr.config).provideDrtSystemInterface
  }
}

