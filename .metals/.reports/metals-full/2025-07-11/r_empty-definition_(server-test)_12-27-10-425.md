file://<WORKSPACE>/server/src/test/scala/controllers/application/ShiftsControllerSpec.scala
empty definition using pc, found symbol in pc: 
semanticdb not found
empty definition using fallback
non-local guesses:

offset: 2368
uri: file://<WORKSPACE>/server/src/test/scala/controllers/application/ShiftsControllerSpec.scala
text:
```scala
package controllers.application

import actors.{DrtParameters, FlightLookupsLike, ManifestLookupsLike, MinuteLookupsLike}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Shift, ShiftAssignments, StaffAssignment, StaffAssignmentLike}
import manifests.ManifestLookupLike
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.Timeout
import org.specs2.mutable.Specification
import play.api.mvc.Headers
import play.api.test.Helpers._
import play.api.test._
import slickdb.{AkkaDbTables, DropInTableLike, DropInsRegistrationTableLike, FeatureGuideTableLike, FeatureGuideViewLike}
import uk.gov.homeoffice.drt.crunchsystem.{ActorsServiceLike, PersistentStateActors}
import uk.gov.homeoffice.drt.db.AggregatedDbTables
import uk.gov.homeoffice.drt.db.dao.{IABFeatureDao, IUserFeedbackDao}
import uk.gov.homeoffice.drt.db.tables.{StaffShiftRow, UserTableLike}
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.Lhr
import uk.gov.homeoffice.drt.service.FeedService
import uk.gov.homeoffice.drt.service.staffing.ShiftAssignmentsService
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}
import upickle.default._

import java.sql.Date
import scala.concurrent.{ExecutionContext, Future}

class ShiftsControllerSpec extends Specification {


  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  case class MockShiftAssignmentsService(shifts: Seq[StaffAssignmentLike]) extends ShiftAssignmentsService {
    override def shiftAssignmentsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments] =
      Future.successful(ShiftAssignments(shifts))

    override def allShiftAssignments: Future[ShiftAssignments] =
      Future.successful(ShiftAssignments(shifts))

    override def updateShiftAssignments(shiftAssignments: Seq[StaffAssignmentLike]): Future[ShiftAssignments] = Future.successful(ShiftAssignments(shifts))
  }


// ...existing code...
var shifts: Seq[Shift] = Seq.empty

case class MockShiftsService() extends uk.gov.homeoffice.drt.service.staffing.ShiftsService {
  override def getShifts(date: LocalDate): Future[Seq[Shift]] = 
    Future.successful(Seq.empty)
    
  override def saveShift(shift: Shift): Future[Boolean] =  @@true
    // Future.successful(shifts :+= shift)
    
  override def deleteShift(terminalName: String, shiftName: String, date: LocalDate): Future[Boolean] = 
    Future.successful(true)
    
  override def updateShift(shift: Shift): Future[Boolean] = 
    Future.successful(true)
}

// ...existing code...

val baseCtrl = new TestDrtModule(Lhr.config).provideDrtSystemInterface
val mockCtrl = new uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface {
  override def shiftsService = new MockShiftsService
  // Delegate all other members to baseCtrl
  override implicit val materializer = baseCtrl.materializer
  override implicit val ec = baseCtrl.ec
  override implicit val system = baseCtrl.system
  override implicit val timeout = baseCtrl.timeout
  override implicit val airportConfig = baseCtrl.airportConfig
  override val aggregatedDb = baseCtrl.aggregatedDb
  override val akkaDb = baseCtrl.akkaDb
  override val params = baseCtrl.params
  override val now = baseCtrl.now
  override val manifestLookupService = baseCtrl.manifestLookupService
  override val manifestLookups = baseCtrl.manifestLookups
  override val flightLookups = baseCtrl.flightLookups
  override val minuteLookups = baseCtrl.minuteLookups
  override val actorService = baseCtrl.actorService
  override val persistentActors = baseCtrl.persistentActors
  override val feedService = baseCtrl.feedService
  override def run(): Unit = baseCtrl.run()
  override val abFeatureService = baseCtrl.abFeatureService
  override val dropInService = baseCtrl.dropInService
  override val dropInRegistrationService = baseCtrl.dropInRegistrationService
  override val featureGuideService = baseCtrl.featureGuideService
  override val featureGuideViewService = baseCtrl.featureGuideViewService
  override val userFeedbackService = baseCtrl.userFeedbackService
  override val userService = baseCtrl.userService
}

  

  // In your test:
  // val mockCtrl = new MockDrtSystemInterface
  // val controller = new ShiftsController(stubControllerComponents(), mockCtrl, mockShiftAssignmentsService)



  "ShiftsController" should {
    val allShifts = ShiftAssignments(Seq(StaffAssignment("shiftName", Terminal("T1"), 0L, 0L, 0, None)))
    val mockShiftAssignmentsService = MockShiftAssignmentsService(allShifts.assignments)
    val mockCtrl = new TestDrtModule(Lhr.config).provideDrtSystemInterface


    //    val shiftsController = new ShiftsController(stubControllerComponents(), mockCtrl, mockShiftAssignmentsService)

    "return Ok when shift is updated successfully" in {

      val shifts = Shift("LHR", "T1", "shiftName", LocalDate(2023, 10, 1), "08:00", "16:00", None, 5, None, None, 0L)

      implicit val writer: Writer[Shift] = macroW[Shift]
      val request = FakeRequest().withTextBody(write(shifts)).withHeaders(Headers("X-Forwarded-Groups" -> "staff:edit,LHR"))
      //      val result = shiftsController.updateShift().apply(request)
      val controller = new ShiftsController(stubControllerComponents(), mockCtrl, mockShiftAssignmentsService)

      //      val shift = Shift("LHR", "T1", "A", 0L, "09:00", "17:00", None, 5, None, None, 0L)
      //      val shiftJson = write(shift)
      //      val authHeader = Headers("X-Forwarded-Groups" -> "staff:edit,LHR")
      //      val request = FakeRequest().withBody(shiftJson).withHeaders(authHeader, "Content-Type" -> "text/plain")

      val result = controller.updateShift()(request)
      status(result) mustEqual OK
    }
  }
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: 