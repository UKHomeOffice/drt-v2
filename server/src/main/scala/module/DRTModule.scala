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
import controllers.application.exports.DesksExportController
import controllers.application.{AirportInfoController, ApplicationInfoController, ConfigController, DebugController, DropInsController, EgateBanksController, EmailNotificationController, ExportsController, FeatureFlagsController, FeedsController, ForecastAccuracyController, ImportsController, ManifestsController, PortStateController, RedListsController, StaffingController, WalkTimeController}
import play.api.Configuration
import play.api.libs.concurrent.AkkaGuiceSupport
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
    bind(classOf[Application]).asEagerSingleton()
    bind(classOf[DesksExportController]).asEagerSingleton()
    bind(classOf[ExportsController]).asEagerSingleton()
    bind(classOf[WalkTimeController]).asEagerSingleton()
    bind(classOf[PortStateController]).asEagerSingleton()
    bind(classOf[ManifestsController]).asEagerSingleton()
    bind(classOf[ImportsController]).asEagerSingleton()
    bind(classOf[FeedsController]).asEagerSingleton()
    bind(classOf[ForecastAccuracyController]).asEagerSingleton()
    bind(classOf[EmailNotificationController]).asEagerSingleton()
    bind(classOf[EgateBanksController]).asEagerSingleton()
    bind(classOf[FeatureFlagsController]).asEagerSingleton()
    bind(classOf[ConfigController]).asEagerSingleton()
    bind(classOf[DebugController]).asEagerSingleton()
    bind(classOf[RedListsController]).asEagerSingleton()
    bind(classOf[ApplicationInfoController]).asEagerSingleton()
    bind(classOf[StaffingController]).asEagerSingleton()
    bind(classOf[AirportInfoController]).asEagerSingleton()
    bind(classOf[WalkTimeController]).asEagerSingleton()
    bind(classOf[DropInsController]).asEagerSingleton()
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
