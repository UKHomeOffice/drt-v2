package uk.gov.homeoffice.drt.testsystem

import actors.DrtParameters
import com.google.inject.Inject
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{DropIn, ShiftAssignments, StaffAssignmentLike}
import manifests.ManifestLookupLike
import manifests.passengers.{BestAvailableManifest, ManifestPaxCount}
import org.apache.pekko.stream.scaladsl.Source
import slickdb._
import uk.gov.homeoffice.drt.{Shift, ShiftMeta, ShiftStaffRolling}
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.db.dao.{IABFeatureDao, IUserFeedbackDao}
import uk.gov.homeoffice.drt.db.tables.{ABFeatureRow, UserFeedbackRow, UserRow, UserTableLike}
import uk.gov.homeoffice.drt.models.{UniqueArrivalKey, UserPreferences}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.service.staffing.{
  IShiftStaffRollingService,
  LegacyShiftAssignmentsService, ShiftMetaInfoService, ShiftsService
}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}
import uk.gov.homeoffice.drt.util.ShiftUtil.localDateFromString

import java.sql.Timestamp
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

case class MockManifestLookupService() extends ManifestLookupLike {
  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] =
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), None))

  override def maybeHistoricManifestPax(arrivalPort: PortCode,
                                        departurePort: PortCode,
                                        voyageNumber: VoyageNumber,
                                        scheduled: SDateLike): Future[(UniqueArrivalKey, Option[ManifestPaxCount])] = {
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), None))
  }
}

case class MockUserTable() extends UserTableLike {

  override def removeUser(email: String)(implicit ec: ExecutionContext): Future[Int] = Future.successful(1)

  override def selectUser(email: String)(implicit ec: ExecutionContext): Future[Option[UserRow]] = Future.successful(Some(UserRow(email, email, email, new Timestamp(SDate.now().millisSinceEpoch), None, None, None, None, None, None, None, None, None, None, None)))

  override def upsertUser(userData: UserRow)(implicit ec: ExecutionContext): Future[Int] = Future.successful(1)

  override def updateCloseBanner(email: String, at: Timestamp)(implicit ec: ExecutionContext): Future[Int] = Future.successful(1)

  override def updateStaffPlanningIntervalMinutes(email: String, periodInterval: Int)(implicit ec: ExecutionContext): Future[Int] = Future.successful(1)

  override def updateUserPreferences(email: String, userPreferences: UserPreferences)(implicit ec: ExecutionContext): Future[Int] = Future.successful(1)
}

case class MockFeatureGuideTable() extends FeatureGuideTableLike {
  override def getAll()(implicit ec: ExecutionContext): Future[String] =
    Future.successful(
      """[{"id":[1],"uploadTime":1686066599088,"fileName":["test1"],"title":["Test1"],"markdownContent":"Here is markdown example","published":true}]"""
    )

  override def selectAll(implicit ec: ExecutionContext): Future[Seq[FeatureGuideRow]] = Future.successful(Seq.empty)

  override def getGuideIdForFilename(filename: String)(implicit ec: ExecutionContext): Future[Option[Int]] = Future.successful(None)
}

case class MockFeatureGuideViewTable() extends FeatureGuideViewLike {
  override def insertOrUpdate(fileId: Int, email: String)(implicit ec: ExecutionContext): Future[String] = Future.successful("")

  override def featureViewed(email: String)(implicit ec: ExecutionContext): Future[Seq[String]] = Future.successful(Seq.empty)
}

case class MockDropInsRegistrationTable() extends DropInsRegistrationTableLike {
  override def createDropInRegistration(email: String, id: String)(implicit ex: ExecutionContext): Future[Int] = Future.successful(1)

  override def getDropInRegistrations(email: String)(implicit ex: ExecutionContext): Future[Seq[DropInsRegistrationRow]] =
    Future.successful(Seq(
      DropInsRegistrationRow(email = "someone@test.com",
        dropInId = 1,
        registeredAt = new Timestamp(1695910303210L),
        emailSentAt = Some(new Timestamp(1695910303210L)))))
}

case class MockDropInTable() extends DropInTableLike {
  override def getDropIns(ids: Seq[String])(implicit ec: ExecutionContext): Future[Seq[DropInRow]] =
    Future.successful(Seq.empty)

  override def getFuturePublishedDropIns()(implicit ec: ExecutionContext): Future[Seq[DropIn]] =
    Future.successful(Seq(DropIn(id = Some(1),
      title = "test",
      startTime = 1696687258000L,
      endTime = 1696692658000L,
      isPublished = true,
      meetingLink = None,
      lastUpdatedAt = 1695910303210L)))

}

case class MockDrtParameters @Inject()() extends DrtParameters {
  override val gateWalkTimesFilePath: Option[String] = None
  override val standWalkTimesFilePath: Option[String] = None
  override val forecastMaxDays: Int = 3
  override val aclDisabled: Boolean = false
  override val aclHost: Option[String] = None
  override val aclUsername: Option[String] = None
  override val aclKeyPath: Option[String] = None
  override val useNationalityBasedProcessingTimes: Boolean = false
  override val isSuperUserMode: Boolean = false
  override val bhxIataEndPointUrl: String = ""
  override val bhxIataUsername: String = ""
  override val cwlIataEndPointUrl: String = ""
  override val cwlIataUsername: String = ""
  override val maybeBhxSoapEndPointUrl: Option[String] = None
  override val maybeLtnLiveFeedUrl: Option[String] = None
  override val maybeLtnLiveFeedUsername: Option[String] = None
  override val maybeLtnLiveFeedPassword: Option[String] = None
  override val maybeLtnLiveFeedToken: Option[String] = None
  override val maybeLtnLiveFeedTimeZone: Option[String] = None
  override val maybeLGWNamespace: Option[String] = None
  override val maybeLGWSASToKey: Option[String] = None
  override val maybeLGWServiceBusUri: Option[String] = None
  override val maybeGlaLiveUrl: Option[String] = None
  override val maybeGlaLiveToken: Option[String] = None
  override val maybeGlaLivePassword: Option[String] = None
  override val maybeGlaLiveUsername: Option[String] = None
  override val useApiPaxNos: Boolean = true
  override val displayRedListInfo: Boolean = false
  override val enableToggleDisplayWaitTimes: Boolean = false
  override val adjustEGateUseByUnderAge: Boolean = false
  override val lcyLiveEndPointUrl: String = ""
  override val lcyLiveUsername: String = ""
  override val lcyLivePassword: String = ""
  override val maybeRemovalCutOffSeconds: Option[FiniteDuration] = None
  override val usePassengerPredictions: Boolean = true
  override val legacyFeedArrivalsBeforeDate: SDateLike = SDate("2024-04-03")
  override val enablePreRetentionPeriodDataDeletion: Boolean = false
  override val retainDataForYears: Int = 5
  override val govNotifyApiKey: String = ""
  override val isTestEnvironment: Boolean = true
  override val enableShiftPlanningChange: Boolean = true
  override val disableDeploymentFairXmax: Boolean = true
  override val stalePredictionHours: FiniteDuration = 24.hours
}

case class MockUserFeedbackDao() extends IUserFeedbackDao {
  override def insertOrUpdate(userFeedbackRow: UserFeedbackRow): Future[Int] = Future.successful(1)

  override def selectAll()(implicit executionContext: ExecutionContext): Future[Seq[UserFeedbackRow]] = Future.successful(Seq())

  override def selectByEmail(email: String): Future[Seq[UserFeedbackRow]] = Future.successful(Seq())

  override def selectAllAsStream(): Source[UserFeedbackRow, _] = Source.empty
}

case class MockAbFeatureDao() extends IABFeatureDao {
  override def insertOrUpdate(aBFeatureRow: ABFeatureRow): Future[Int] = Future.successful(1)

  override def getABFeatures: Future[Seq[ABFeatureRow]] = Future.successful(Seq.empty)

  override def getABFeaturesByEmailForFunction(email: String, functionName: String): Future[Seq[ABFeatureRow]] = Future.successful(Seq.empty)

  override def getABFeatureByFunctionName(functionName: String): Future[Seq[ABFeatureRow]] = Future.successful(Seq.empty)
}

case class MockShiftMetaInfoService()(implicit ec: ExecutionContext) extends ShiftMetaInfoService {
  var mockShiftMetaSeq: Seq[ShiftMeta] = Seq.empty[ShiftMeta]

  override def insertShiftMetaInfo(shiftMeta: ShiftMeta): Future[Int] = {
    mockShiftMetaSeq = mockShiftMetaSeq :+ shiftMeta
    Future.successful(mockShiftMetaSeq).map(_.size)
  }

  override def getShiftMetaInfo(port: String, terminal: String): Future[Option[ShiftMeta]] = {
    Future(mockShiftMetaSeq.find(s => port == s.port && terminal == s.terminal))
  }

  override def updateShiftAssignmentsMigratedAt(port: String, terminal: String, shiftAssignmentsMigratedAt: Option[Long]): Future[Option[ShiftMeta]] =
    Future.successful(mockShiftMetaSeq.find(s => port == s.port && terminal == s.terminal).map { sm =>
      val updatedShiftMeta = sm.copy(shiftAssignmentsMigratedAt = shiftAssignmentsMigratedAt)
      mockShiftMetaSeq = mockShiftMetaSeq.filterNot(s => port == s.port && terminal == s.terminal) :+ updatedShiftMeta
      updatedShiftMeta
    })
}

case class MockShiftAssignmentsService(shifts: Seq[StaffAssignmentLike]) extends LegacyShiftAssignmentsService {
  var shiftsAssignments: Seq[StaffAssignmentLike] = shifts

  override def shiftAssignmentsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments] =
    Future.successful(ShiftAssignments(shiftsAssignments))

  override def allShiftAssignments: Future[ShiftAssignments] =
    Future.successful(ShiftAssignments(shiftsAssignments))

  override def updateShiftAssignments(shiftAssignments: Seq[StaffAssignmentLike]): Future[ShiftAssignments] = {
    shiftsAssignments = shiftsAssignments ++ shiftAssignments
    Future.successful(ShiftAssignments(shiftsAssignments))
  }
}

case class MockStaffShiftsService()(implicit ec: ExecutionContext) extends ShiftsService {
  var shiftSeq = Seq.empty[Shift]

  override def getShifts(port: String, terminal: String): Future[Seq[Shift]] = {
    Future.successful(shiftSeq)
  }

  override def deleteShift(port: String, terminal: String, shiftName: String): Future[Int] = {
    shiftSeq = Seq.empty
    Future.successful(shiftSeq.size)
  }

  override def saveShift(shifts: Seq[Shift]): Future[Int] = {
    shiftSeq = Seq.empty[Shift]
    shiftSeq = shiftSeq ++ shifts
    Future.successful(shiftSeq.size)
  }

  override def deleteShifts(): Future[Int] = {
    shiftSeq = Seq.empty
    Future.successful(shiftSeq.size)
  }

  override def updateShift(previousShift: Shift, shift: Shift): Future[Shift] = Future.successful(shift)

  override def getActiveShifts(port: String, terminal: String, date: Option[String]): Future[Seq[Shift]] = Future.successful {
    val localDate: LocalDate = localDateFromString(date)
    shiftSeq.filter { shift =>
      shift.startDate.compare(localDate) <= 0 &&
        (shift.endDate.isEmpty || shift.endDate.exists(_.compare(localDate) >= 0))
    }
  }

  override def getShift(port: String, terminal: String, shiftName: String, startDate: LocalDate): Future[Option[Shift]] = {
    val shift = shiftSeq.find(s => s.shiftName == shiftName && s.port == port && s.terminal == terminal)
    Future.successful(shift)
  }

  override def getOverlappingStaffShifts(port: String, terminal: String, shift: Shift): Future[Seq[Shift]] = {
    val overlappingShifts = shiftSeq.filter { s =>
      s.port == port &&
        s.terminal == terminal &&
        (s.endDate.isEmpty || s.endDate.exists(_ > shift.startDate))
    }.sortBy(_.startDate)

    Future.successful(overlappingShifts)
  }

  override def latestStaffShiftForADate(port: String,
                                        terminal: String,
                                        startDate: LocalDate,
                                        startTime: String)(implicit ec: ExecutionContext): Future[Option[Shift]] =
    Future.successful(shiftSeq.filter(s => s.port == port && s.terminal == terminal && s.startDate == startDate && s.startTime == startTime).lastOption)

  override def createNewShiftWhileEditing(previousShift: Shift, shiftRow: Shift)(implicit ec: ExecutionContext): Future[(Shift, Option[Shift])] =
    Future.successful((shiftRow, None))

  override def getActiveShiftsForViewRange(port: String,
                                           terminal: String,
                                           dayRange: Option[String],
                                           date: Option[String]): Future[Seq[Shift]] = Future.successful(shiftSeq)

}

case class MockShiftStaffRollingService()(implicit ec: ExecutionContext) extends IShiftStaffRollingService {
  var shiftStaffRollingSeq = Seq.empty[ShiftStaffRolling]

  override def upsertShiftStaffRolling(shiftStaffRolling: ShiftStaffRolling): Future[Int] = {
    shiftStaffRollingSeq = shiftStaffRollingSeq :+ shiftStaffRolling
    Future.successful(shiftStaffRollingSeq).map(_.size)
  }

  override def getShiftStaffRolling(port: String, terminal: String): Future[Seq[ShiftStaffRolling]] =
    Future.successful(shiftStaffRollingSeq.filter(s => s.port == port && s.terminal == terminal))


}
