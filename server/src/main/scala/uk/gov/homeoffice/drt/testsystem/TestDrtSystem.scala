
package uk.gov.homeoffice.drt.testsystem

import actors._
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Status}
import akka.pattern.{StatusReply, ask}
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import akka.stream.scaladsl.Source
import akka.stream.{KillSwitch, Materializer}
import akka.util.Timeout
import com.google.inject.Inject
import drt.shared.DropIn
import manifests.passengers.{BestAvailableManifest, ManifestPaxCount}
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import slickdb._
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.auth.Roles.Role
import uk.gov.homeoffice.drt.crunchsystem.{DrtSystemInterface, ReadRouteUpdateActorsLike}
import uk.gov.homeoffice.drt.db._
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode}
import uk.gov.homeoffice.drt.service.ApplicationService
import uk.gov.homeoffice.drt.testsystem.RestartActor.AddResetActors
import uk.gov.homeoffice.drt.testsystem.TestActors._
import uk.gov.homeoffice.drt.testsystem.crunchsystem.TestPersistentStateActors
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}

import java.sql.Timestamp
import javax.inject.Singleton
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps

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
}

case class MockFeatureGuideTable() extends FeatureGuideTableLike {
  override def getAll()(implicit ec: ExecutionContext): Future[String] =
    Future.successful("""[{"id":[1],"uploadTime":1686066599088,"fileName":["test1"],"title":["Test1"],"markdownContent":"Here is markdown example","published":true}]""")

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
  override val refreshArrivalsOnStart: Boolean = false
  override val flushArrivalsOnStart: Boolean = false
  override val recrunchOnStart: Boolean = false
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
}


@Singleton
case class TestDrtSystem @Inject()(airportConfig: AirportConfig,
                                   params: DrtParameters,
                                   now: () => SDateLike)
                                  (implicit val materializer: Materializer,
                                   val ec: ExecutionContext,
                                   val system: ActorSystem,
                                   val timeout: Timeout) extends DrtSystemInterface {

  log.warn("Using test System")
  override val minuteLookups: MinuteLookupsLike = TestMinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)
  override val flightLookups: FlightLookupsLike = TestFlightLookups(system, now, airportConfig.queuesByTerminal, paxFeedSourceOrder)
  override val manifestLookupService: ManifestLookupLike = MockManifestLookupService()
  override val manifestLookupsService: ManifestLookupsLike = ManifestLookups(system)
  lazy override val actorService: ReadRouteUpdateActorsLike = TestActorService(journalType,
    airportConfig,
    now, params,
    flightLookups,
    minuteLookups)

  val persistentActors: TestPersistentStateActors = TestPersistentStateActors(
    system,
    now,
    airportConfig.minutesToCrunch,
    airportConfig.crunchOffsetMinutes,
    params.forecastMaxDays,
    manifestLookupsService,
  )
  val applicationService: ApplicationService = ApplicationService(
    journalType = journalType,
    airportConfig = airportConfig,
    now = now,
    params = params,
    config = config,
    db = db,
    feedService = feedService,
    manifestLookups = manifestLookupsService,
    manifestLookupService = manifestLookupService,
    minuteLookups = minuteLookups,
    readActorService = actorService,
    persistentStateActors = persistentActors
  )

  lazy override val db: Tables = AggregateDbH2

  override val userService: UserTableLike = MockUserTable()
  override val featureGuideService: FeatureGuideTableLike = MockFeatureGuideTable()
  override val featureGuideViewService: FeatureGuideViewLike = MockFeatureGuideViewTable()
  override val dropInService: DropInTableLike = MockDropInTable()
  override val dropInRegistrationService: DropInsRegistrationTableLike = MockDropInsRegistrationTable()
//  val testDrtSystemActor: TestDrtSystemActorsLike = TestDrtSystemActors(applicationService, feedService, actorService, persistentActors)


  override def getRoles(config: Configuration,
                        headers: Headers,
                        session: Session): Set[Role] = TestUserRoleProvider.getRoles(config, headers, session)

  override def run(): Unit = {
    applicationService.run()
  }


  override val userFeedbackService: IUserFeedbackDao = MockUserFeedbackDao()
  override val abFeatureService: IABFeatureDao = MockAbFeatureDao()
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

object RestartActor {
  case class AddResetActors(actors: Iterable[ActorRef])
}

class RestartActor(startSystem: () => List[KillSwitch]) extends Actor with ActorLogging {

  private lazy val persistenceTestKit: PersistenceTestKit = PersistenceTestKit(context.system)

  private var currentKillSwitches: List[KillSwitch] = List()
  private var actorsToReset: Seq[ActorRef] = Seq.empty
  private var maybeReplyTo: Option[ActorRef] = None

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  override def receive: Receive = {
    case AddResetActors(actors) =>
      actorsToReset = actorsToReset ++ actors

    case ResetData =>
      maybeReplyTo = Option(sender())
      log.info(s"About to shut down everything. Pressing kill switches")

      currentKillSwitches.zipWithIndex.foreach { case (ks, idx) =>
        log.info(s"Kill switch ${idx + 1}")
        ks.shutdown()
      }

      resetInMemoryData()

      val resetFutures = actorsToReset
        .map(_.ask(ResetData)(new Timeout(3 second)))

      Future.sequence(resetFutures).onComplete { _ =>
        log.info(s"Restarting system")
        startTestSystem()
        maybeReplyTo.foreach { k =>
          log.info(s"Sending Ack to sender")
          k ! StatusReply.Ack
        }
        maybeReplyTo = None
      }

    case Status.Success(_) =>
      log.info(s"Got a Status acknowledgement from InMemoryJournalStorage")

    case Status.Failure(t) =>
      log.error(s"Got a failure message: ${t.getMessage}")

    case StartTestSystem =>
      startTestSystem()

    case u =>
      log.error(s"Received unexpected message: ${u.getClass}")
  }

  private def startTestSystem(): Unit = currentKillSwitches = startSystem()

  private def resetInMemoryData(): Unit = persistenceTestKit.clearAll()
}

case object StartTestSystem
