package drt.client.components

import drt.shared.{AirportConfigs, PortCode}
import uk.gov.homeoffice.drt.auth.Roles.Role


object RestrictedAccessByPortPage {
  def allPortsAccessible(roles: Set[Role]): Set[PortCode] = AirportConfigs.allPortConfigs
    .filter(airportConfig => roles.contains(airportConfig.role)).map(_.portCode).toSet
}
