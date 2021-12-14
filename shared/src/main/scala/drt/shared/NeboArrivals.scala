package drt.shared

import uk.gov.homeoffice.drt.ports.PortCode


case class RedListPassengers(flightCode: String, portCode: PortCode, scheduled: SDateLike, urns: Seq[String])

case class NeboArrivals(arrivalRedListPassengers: Map[String, Set[String]])

object NeboArrivals {
  val empty = NeboArrivals(Map[String, Set[String]]().empty)
}