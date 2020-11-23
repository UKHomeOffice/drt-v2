package drt.client.components

import drt.shared.{AirportConfig, AirportConfigs, PortCode}
import org.scalajs.dom
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.Role

object RestrictedAccessByPortPage {
  val allAirportConfigsToDisplay: List[AirportConfig] = AirportConfigs.allPortConfigs diff AirportConfigs.testPorts
  val allPorts: List[PortCode] = AirportConfigs.allPortConfigs.map(config => config.portCode)
  val urlLowerCase: String = dom.document.URL.toLowerCase
  val portRequested: PortCode = allPorts
    .find(port => urlLowerCase.contains(s"${port.toString.toLowerCase}"))
    .getOrElse(PortCode("InvalidPortCode"))

  def allPortsAccessible(roles: Set[Role]): Set[PortCode] = AirportConfigs.allPortConfigs
    .filter(airportConfig => roles.contains(airportConfig.role)).map(_.portCode).toSet

  def userCanAccessPort(loggedInUser: LoggedInUser, portCode: PortCode): Boolean = AirportConfigs.
    allPortConfigs
    .find(_.portCode == portCode)
    .exists(c => loggedInUser.hasRole(c.role))

  def url(port: PortCode): String = urlLowerCase.replace(portRequested.toString.toLowerCase, port.toString.toLowerCase)
}
