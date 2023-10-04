
package controllers

import actors.{DrtSystemInterface, ProdDrtParameters, ProdDrtSystem}
import akka.actor.ActorSystem
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.stream.Materializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import drt.shared.DrtPortConfigs
import play.api.Configuration
import test.{MockDrtParameters, TestDrtSystem}
import uk.gov.homeoffice.drt.ports.AirportConfig

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

object DrtActorSystem extends AirportConfProvider {
  val config: Configuration = new Configuration(ConfigFactory.load)
  val isTestEnvironment: Boolean = config.getOptional[String]("env").getOrElse("prod") == "test"
  implicit val actorSystem: ActorSystem = if (isTestEnvironment) {
    ActorSystem("DRT", PersistenceTestKitPlugin.config.withFallback(ConfigFactory.load()))
  } else {
    ActorSystem("DRT")
  }
  implicit val mat: Materializer = Materializer.createMaterializer(actorSystem)
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(5.seconds)
  lazy val drtSystem: DrtSystemInterface =
    if (isTestEnvironment) {
      drtTestSystem
    } else {
      drtProdSystem
    }

  lazy val drtTestSystem: TestDrtSystem = TestDrtSystem(airportConfig, MockDrtParameters())
  lazy val drtProdSystem: ProdDrtSystem = ProdDrtSystem(airportConfig, ProdDrtParameters(config))

  private def getPortConfFromEnvVar: AirportConfig = DrtPortConfigs.confByPort(portCode)

  override def airportConfig: AirportConfig = {
    val configForPort = getPortConfFromEnvVar.copy(
      contactEmail = contactEmail,
      outOfHoursContactPhone = oohPhone,
      useTimePredictions = useTimePredictions,
      noLivePortFeed = noLivePortFeed,
      aclDisabled = aclDisabled,
      idealStaffAsDefault = idealStaffAsDefault
    )

    configForPort.assertValid()

    configForPort
  }
}
