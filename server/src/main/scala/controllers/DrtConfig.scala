package controllers

import com.typesafe.config.ConfigFactory
import drt.shared.DrtPortConfigs
import play.api.Configuration
import uk.gov.homeoffice.drt.ports.AirportConfig

class DrtConfigSystem extends AirportConfProvider {
  lazy val config: Configuration = new Configuration(ConfigFactory.load)

  private def getPortConfFromEnvVar: AirportConfig = DrtPortConfigs.confByPort(portCode)

  def govNotifyApiKey = config.get[String]("notifications.gov-notify-api-key")

  def isTestEnvironment: Boolean = config.getOptional[String]("env").getOrElse("prod") == "test"

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
