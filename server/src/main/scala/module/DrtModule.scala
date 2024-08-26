package module

import actors.DrtParameters
import actors.persistent.staffing.ShiftsActor
import akka.Done
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.util.Timeout
import com.google.inject.{AbstractModule, Provides}
import com.typesafe.config.ConfigFactory
import controllers.application._
import controllers.application.exports.{DesksExportController, FlightsExportController}
import controllers.{AirportConfigProvider, Application}
import email.GovNotifyEmail
import play.api.Configuration
import play.api.libs.concurrent.AkkaGuiceSupport
import uk.gov.homeoffice.drt.crunchsystem.{DrtSystemInterface, ProdDrtSystem}
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.service.staffing._
import uk.gov.homeoffice.drt.testsystem.TestDrtSystem
import uk.gov.homeoffice.drt.testsystem.controllers.TestController
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import javax.inject.Singleton
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class DrtModule extends AbstractModule with AkkaGuiceSupport {
  val now: () => SDateLike = () => SDate.now()

  val config: Configuration = new Configuration(ConfigFactory.load)

  val airportConfig: AirportConfig = AirportConfigProvider(config)

  lazy val drtParameters: DrtParameters = DrtParameters(config)

  private lazy val drtTestSystem: TestDrtSystem = TestDrtSystem(airportConfig, drtParameters, now)
  private lazy val drtProdSystem: ProdDrtSystem = ProdDrtSystem(airportConfig, drtParameters, now)

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(10.seconds)

  override def configure(): Unit = {
    if (drtParameters.isTestEnvironment) {
      bind(classOf[TestController]).asEagerSingleton()
    }
    bind(classOf[AirportInfoController]).asEagerSingleton()
    bind(classOf[AlertsController]).asEagerSingleton()
    bind(classOf[Application]).asEagerSingleton()
    bind(classOf[ApplicationInfoController]).asEagerSingleton()
    bind(classOf[ConfigController]).asEagerSingleton()
    bind(classOf[ContactDetailsController]).asEagerSingleton()
    bind(classOf[DebugController]).asEagerSingleton()
    bind(classOf[DesksExportController]).asEagerSingleton()
    bind(classOf[DropInsController]).asEagerSingleton()
    bind(classOf[ExportsController]).asEagerSingleton()
    bind(classOf[EmailNotificationController]).asEagerSingleton()
    bind(classOf[EgateBanksController]).asEagerSingleton()
    bind(classOf[FeedsController]).asEagerSingleton()
    bind(classOf[FeatureFlagsController]).asEagerSingleton()
    bind(classOf[FlightsExportController]).asEagerSingleton()
    bind(classOf[ForecastAccuracyController]).asEagerSingleton()
    bind(classOf[ImportsController]).asEagerSingleton()
    bind(classOf[ManifestsController]).asEagerSingleton()
    bind(classOf[PortStateController]).asEagerSingleton()
    bind(classOf[RedListsController]).asEagerSingleton()
    bind(classOf[StaffingController]).asEagerSingleton()
    bind(classOf[SummariesController]).asEagerSingleton()
    bind(classOf[SimulationsController]).asEagerSingleton()
    bind(classOf[WalkTimeController]).asEagerSingleton()
  }

  @Provides
  def provideTestDrtSystem = drtTestSystem

  @Provides
  @Singleton
  implicit val provideActorSystem: ActorSystem = if (drtParameters.isTestEnvironment) {
    ActorSystem("DRT-Module", PersistenceTestKitPlugin.config.withFallback(ConfigFactory.load()))
  } else {
    ActorSystem("DRT-Module")
  }

  @Provides
  @Singleton
  def provideDrtSystemInterface: DrtSystemInterface = {
    if (drtParameters.isTestEnvironment)
      drtTestSystem
    else
      drtProdSystem
  }

  @Provides
  @Singleton
  def provideShiftsService: ShiftsService = ShiftsServiceImpl(
    provideDrtSystemInterface.actorService.liveShiftsReadActor,
    provideDrtSystemInterface.actorService.shiftsSequentialWritesActor,
    ShiftsServiceImpl.pitActor,
  )

  @Provides
  @Singleton
  def provideFixedPointsService: FixedPointsService = FixedPointsServiceImpl(
    provideDrtSystemInterface.actorService.liveFixedPointsReadActor,
    provideDrtSystemInterface.actorService.fixedPointsSequentialWritesActor,
    FixedPointsServiceImpl.pitActor,
  )

  @Provides
  @Singleton
  def provideStaffMovementsService: StaffMovementsService = StaffMovementsServiceImpl(
    provideDrtSystemInterface.actorService.liveStaffMovementsReadActor,
    provideDrtSystemInterface.actorService.staffMovementsSequentialWritesActor,
    StaffMovementsServiceImpl.pitActor,
  )

  @Provides
  def provideGovNotifyEmail: GovNotifyEmail = new GovNotifyEmail(drtParameters.govNotifyApiKey)

  @Provides
  def minimumStaffingService: MinimumStaffingService = MinimumStaffingService(
    portCode = airportConfig.portCode,
    now = now,
    forecastMaxDays = drtParameters.forecastMaxDays,
    getTerminalConfig = provideDrtSystemInterface.applicationService.getTerminalConfig,
    updateTerminalConfig = provideDrtSystemInterface.applicationService.updateTerminalConfig,
    updateStaffingNumbers = (terminal, start, end, newValue, oldValue) => {
      val request = ShiftsActor.SetMinimumStaff(terminal, start, end, newValue, oldValue)
      provideDrtSystemInterface.actorService.shiftsSequentialWritesActor.ask(request).map(_ => Done)
    },
  )
}
