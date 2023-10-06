package module

import actors.{DrtSystemInterface, ProdDrtParameters, ProdDrtSystem}
import akka.actor.ActorSystem
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.stream.Materializer
import akka.util.Timeout
import com.google.inject.{AbstractModule, Provides}
import com.typesafe.config.ConfigFactory
import controllers.Application
import controllers.DrtActorSystem.airportConfig
import controllers.application.exports.{DesksExportController, FlightsExportController, SummariesExportController}
import controllers.application.{AirportInfoController, AlertsController, ApplicationInfoController, ConfigController, ContactDetailsController, DebugController, DropInsController, EgateBanksController, EmailNotificationController, ExportsController, FeatureFlagsController, FeedsController, ForecastAccuracyController, ImportsController, ManifestsController, PortStateController, RedListsController, SimulationsController, StaffingController, WalkTimeController}
import play.api.Configuration
import play.api.libs.concurrent.AkkaGuiceSupport
import test.controllers.TestController
import test.{MockDrtParameters, TestDrtSystem}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class DRTModule extends AbstractModule with AkkaGuiceSupport {
  implicit lazy val config: Configuration = new Configuration(ConfigFactory.load)
  lazy val isTestEnvironment: Boolean = config.getOptional[String]("env").getOrElse("prod") == "test"
  lazy val drtTestSystem: TestDrtSystem = TestDrtSystem(airportConfig, MockDrtParameters())
  lazy val drtProdSystem: ProdDrtSystem = ProdDrtSystem(airportConfig, ProdDrtParameters(config))

  implicit lazy val mat: Materializer = Materializer.createMaterializer(provideActorSystem)
  implicit lazy val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit lazy val timeout: Timeout = new Timeout(5.seconds)


  override def configure(): Unit = {
    bind(classOf[TestController]).asEagerSingleton()
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
    bind(classOf[SummariesExportController]).asEagerSingleton()
    bind(classOf[SimulationsController]).asEagerSingleton()
    bind(classOf[WalkTimeController]).asEagerSingleton()
  }

  @Provides
  implicit val provideActorSystem: ActorSystem = if (isTestEnvironment) {
    ActorSystem("DRT-Module", PersistenceTestKitPlugin.config.withFallback(ConfigFactory.load()))
  } else {
    ActorSystem("DRT-Module")
  }

  @Provides
  def provideDrtSystemInterface: DrtSystemInterface =
    if (isTestEnvironment) {
      drtTestSystem
    } else {
      drtProdSystem
    }

}
