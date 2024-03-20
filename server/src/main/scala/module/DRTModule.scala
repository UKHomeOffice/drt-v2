package module

import actors.ProdDrtParameters
import akka.actor.ActorSystem
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.util.Timeout
import com.google.inject.{AbstractModule, Provides}
import com.typesafe.config.ConfigFactory
import controllers.application._
import controllers.application.exports.{DesksExportController, FlightsExportController}
import controllers.{Application, DrtActorSystem}
import email.GovNotifyEmail
import play.api.Configuration
import play.api.libs.concurrent.AkkaGuiceSupport
import uk.gov.homeoffice.drt.crunchsystem.{DrtSystemInterface, ProdDrtSystem}
import uk.gov.homeoffice.drt.testsystem.controllers.TestController
import uk.gov.homeoffice.drt.testsystem.{MockDrtParameters, TestDrtSystem}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import javax.inject.Singleton
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
class DRTModule extends AbstractModule with AkkaGuiceSupport {
  val config: Configuration = new Configuration(ConfigFactory.load)

  val isTestEnvironment: Boolean = config.getOptional[String]("env").getOrElse("prod") == "test"
  val airportConfig = DrtActorSystem.airportConfig

  lazy val mockDrtParameters: MockDrtParameters = MockDrtParameters()

  val now: () => SDateLike = () => SDate.now()

  private lazy val drtTestSystem: TestDrtSystem = TestDrtSystem(airportConfig, mockDrtParameters, now)
  private lazy val drtProdSystem: ProdDrtSystem = ProdDrtSystem(airportConfig, ProdDrtParameters(config), now)

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(10.seconds)

  override def configure(): Unit = {
    if (isTestEnvironment) {
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
  implicit val provideActorSystem: ActorSystem = if (isTestEnvironment) {
    ActorSystem("DRT-Module", PersistenceTestKitPlugin.config.withFallback(ConfigFactory.load()))
  } else {
    ActorSystem("DRT-Module")
  }

  @Provides
  @Singleton
  def provideDrtSystemInterface: DrtSystemInterface = {
    if (isTestEnvironment)
      drtTestSystem
    else
      drtProdSystem
  }

  @Provides
  def provideGovNotifyEmail: GovNotifyEmail = new GovNotifyEmail(config.get[String]("notifications.gov-notify-api-key"))
}
