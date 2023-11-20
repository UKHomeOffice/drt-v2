
package controllers

import actors.ProdDrtParameters
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
