package module

import actors.{DrtSystemInterface, ProdDrtParameters, ProdDrtSystem}
import akka.actor.ActorSystem
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.stream.Materializer
import akka.util.Timeout
import com.google.inject.{AbstractModule, Provides}
import com.typesafe.config.ConfigFactory
import controllers.DropInsController
import controllers.DrtActorSystem.airportConfig
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
