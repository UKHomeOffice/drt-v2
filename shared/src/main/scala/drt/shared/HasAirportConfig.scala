package drt.shared

import drt.shared.airportconfig.{Test, Test2}
import uk.gov.homeoffice.drt.ports.{AirportConfig, AirportConfigLike, PortCode}
import upickle.default._


case class ContactDetails(supportEmail: Option[String], oohPhone: Option[String])

object ContactDetails {
  implicit val rw: ReadWriter[ContactDetails] = macroRW
}

case class OutOfHoursStatus(localTime: String, isOoh: Boolean)

object OutOfHoursStatus {
  implicit val rw: ReadWriter[OutOfHoursStatus] = macroRW
}

object DrtPortConfigs {

  import uk.gov.homeoffice.drt.ports.config._

  private val testPorts: List[AirportConfigLike] = List(Test, Test2)
  private val allPorts: List[AirportConfigLike] = AirportConfigs.allPorts ::: testPorts

  private val allPortConfigs: List[AirportConfig] = allPorts.map(_.config)

  val confByPort: Map[PortCode, AirportConfig] = allPortConfigs.map(c => (c.portCode, c)).toMap
}
