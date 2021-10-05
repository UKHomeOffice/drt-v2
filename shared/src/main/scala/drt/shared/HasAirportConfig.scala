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

trait HasAirportConfig {
  val airportConfig: AirportConfig
}

case class CarrierCode(code: String) {
  override def toString: String = code
}

object CarrierCode {
  implicit val rw: ReadWriter[CarrierCode] = macroRW
}

object DrtPortConfigs {

  import uk.gov.homeoffice.drt.ports.config._

  val testPorts: List[AirportConfigLike] = List(Test, Test2)
  val allPorts: List[AirportConfigLike] = AirportConfigs.allPorts ::: testPorts

  val allPortConfigs: List[AirportConfig] = allPorts.map(_.config)
  val testPortConfigs: List[AirportConfig] = testPorts.map(_.config)

  def portGroups: List[String] = allPortConfigs.filterNot(testPorts.contains).map(_.portCode.toString.toUpperCase).sorted

  val confByPort: Map[PortCode, AirportConfig] = allPortConfigs.map(c => (c.portCode, c)).toMap
}
