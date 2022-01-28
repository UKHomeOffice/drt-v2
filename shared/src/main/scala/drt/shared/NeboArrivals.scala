package drt.shared

import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.SDateLike


case class RedListPassengers(flightCode: String, portCode: PortCode, scheduled: SDateLike, urns: Seq[String])

case class NeboArrivals(urns: Set[String])

object NeboArrivals {
  val empty: NeboArrivals = NeboArrivals(Set[String]().empty)
}
