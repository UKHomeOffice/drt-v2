package drt.client.components

import drt.shared.{AirportConfig, AirportConfigs, PortCode}
import org.scalajs.dom
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.Role

trait AppUrlLike {
  val allPorts: List[PortCode] = AirportConfigs.allPortConfigs.map(config => config.portCode)
  val url: String
  val baseUrl: String = url.split("/").reverse.headOption.getOrElse("http://localhost")
  val currentPort: PortCode = allPorts
    .find(port => url.contains(s"${port.toString.toLowerCase}"))
    .getOrElse(PortCode("InvalidPortCode"))

  def urlForPort(port: PortCode): String = {
    baseUrl.replace(currentPort.toString.toLowerCase, port.toString.toLowerCase)
  }
}

case class AppUrls(url: String) extends AppUrlLike

object RestrictedAccessByPortPage {
  val urls: AppUrls = AppUrls(dom.document.URL.toLowerCase)

  def allPortsAccessible(roles: Set[Role]): Set[PortCode] = AirportConfigs.allPortConfigs
    .filter(airportConfig => roles.contains(airportConfig.role)).map(_.portCode).toSet

  def userCanAccessPort(loggedInUser: LoggedInUser, portCode: PortCode): Boolean = AirportConfigs.
    allPortConfigs
    .find(_.portCode == portCode)
    .exists(c => loggedInUser.hasRole(c.role))

  def url(port: PortCode): String = urlLowerCase.replace(portRequested.toString.toLowerCase, port.toString.toLowerCase)
}
