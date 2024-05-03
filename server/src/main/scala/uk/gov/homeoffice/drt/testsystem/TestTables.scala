package uk.gov.homeoffice.drt.testsystem

import actors.DrtParameters
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import drt.shared.DropIn
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import manifests.passengers.{BestAvailableManifest, ManifestPaxCount}
import slickdb.{DropInRow, DropInTableLike, DropInsRegistrationRow, DropInsRegistrationTableLike, FeatureGuideRow, FeatureGuideTableLike, FeatureGuideViewLike, UserRow, UserTableLike}
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.db.{ABFeatureRow, IABFeatureDao, IUserFeedbackDao, UserFeedbackRow}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

case class MockManifestLookupService() extends ManifestLookupLike {
  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] =
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), None))

  override def historicManifestPax(arrivalPort: PortCode,
                                   departurePort: PortCode,
                                   voyageNumber: VoyageNumber,
                                   scheduled: SDateLike): Future[(UniqueArrivalKey, Option[ManifestPaxCount])] = {
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), None))
  }
}

case class MockUserTable() extends UserTableLike {

  override def removeUser(email: String)(implicit ec: ExecutionContext): Future[Int] = Future.successful(1)

  override def selectUser(email: String)(implicit ec: ExecutionContext): Future[Option[UserRow]] = Future.successful(None)

  override def upsertUser(userData: UserRow)(implicit ec: ExecutionContext): Future[Int] = Future.successful(1)

  override def updateCloseBanner(email: String, at: Timestamp)(implicit ec: ExecutionContext): Future[Int] = Future.successful(1)

  override def updateStaffPlanningIntervalMinutes(email: String, periodInterval: Int)(implicit ec: ExecutionContext): Future[Int] = Future.successful(1)
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
  override val adjustEGateUseByUnder12s: Boolean = false
  override val lcyLiveEndPointUrl: String = ""
  override val lcyLiveUsername: String = ""
  override val lcyLivePassword: String = ""
  override val maybeRemovalCutOffSeconds: Option[FiniteDuration] = None
  override val usePassengerPredictions: Boolean = true
  override val legacyFeedArrivalsBeforeDate: SDateLike = SDate("2024-04-03")
  override val govNotifyApiKey: String = ""
  override val isTestEnvironment: Boolean = true
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

