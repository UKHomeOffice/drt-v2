package controllers

import com.typesafe.config.ConfigFactory
import drt.shared.DrtPortConfigs
import play.api.Configuration
import uk.gov.homeoffice.drt.ports.AirportConfig

object DrtActorSystem extends AirportConfProvider {
  val config: Configuration = new Configuration(ConfigFactory.load)

  private def getPortConfFromEnvVar: AirportConfig = DrtPortConfigs.confByPort(portCode)

  override def airportConfig: AirportConfig = {
    val configForPort = getPortConfFromEnvVar.copy(
      contactEmail = contactEmail,
      outOfHoursContactPhone = oohPhone,
      useTimePredictions = true,
      noLivePortFeed = noLivePortFeed,
      aclDisabled = aclDisabled,
      idealStaffAsDefault = idealStaffAsDefault
    )

    configForPort.assertValid()

    configForPort
  }
}
