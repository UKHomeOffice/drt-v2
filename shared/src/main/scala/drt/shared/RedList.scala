package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.{T2, T3, T5, Terminal}
import drt.shared.api.PassengerInfoSummary

import scala.collection.immutable.Map

object RedList {
  def redListOriginWorkloadExcluded(portCode: PortCode, terminal: Terminal): Boolean =
    portCode == PortCode("LHR") && List(T2, T5).contains(terminal)

  def countryCodesByName(date: MillisSinceEpoch): Map[String, String] =
    if (date >= 1628204400000L) Map(
//    if (date >= 1628377200000L) Map(
      "Afghanistan" -> "AFG",
      "Angola" -> "AGO",
      "Argentina" -> "ARG",
      "Bahrain" -> "BHR",
      "Bangladesh" -> "BGD",
      "Bolivia" -> "BOL",
      "Botswana" -> "BWA",
      "Brazil" -> "BRA",
      "Burundi" -> "BDI",
      "Cape Verde" -> "CPV",
      "Chile" -> "CHL",
      "Colombia" -> "COL",
      "Costa Rica" -> "CRI",
      "Cuba" -> "CUB",
      "Democratic Republic of the Congo" -> "COD",
      "Dominican Republic" -> "DOM",
      "Ecuador" -> "ECU",
      "Egypt" -> "EGY",
      "Eritrea" -> "ERI",
      "Eswatini" -> "SWZ",
//      "Ethiopia" -> "ETH",
      "French Guiana" -> "GUF",
      "Guyana" -> "GUY",
      "Haiti" -> "HTI",
      "India" -> "IND",
      "Indonesia" -> "IDN",
      "Kenya" -> "KEN",
      "Lesotho" -> "LSO",
      "Malawi" -> "MWI",
      "Maldives" -> "MDV",
      "Mongolia" -> "MNG",
      "Mozambique" -> "MOZ",
      "Myanmar" -> "MMR",
      "Namibia" -> "NAM",
      "Nepal" -> "NPL",
      "Oman" -> "OMN",
      "Pakistan" -> "PAK",
      "Panama" -> "PAN",
      "Paraguay" -> "PRY",
      "Peru" -> "PER",
      "Philippines" -> "PHL",
      "Qatar" -> "QAT",
      "Rwanda" -> "RWA",
      "Seychelles" -> "SYC",
      "Sierra Leone" -> "SLE",
      "Somalia" -> "SOM",
      "South Africa" -> "ZAF",
      "Sri Lanka" -> "LKA",
      "Sudan" -> "SDN",
      "Suriname" -> "SUR",
      "Tanzania" -> "TZA",
      "Tunisia" -> "TUN",
      "Turkey" -> "TUR",
      "Trinidad and Tobago" -> "TTO",
      "Uganda" -> "UGA",
      "United Arab Emirates" -> "ARE",
      "Uruguay" -> "URY",
      "Venezuela" -> "VEN",
      "Zambia" -> "ZMB",
      "Zimbabwe" -> "ZWE",
    ) else Map(
      "Afghanistan" -> "AFG",
      "Angola" -> "AGO",
      "Argentina" -> "ARG",
      "Bahrain" -> "BHR",
      "Bangladesh" -> "BGD",
      "Bolivia" -> "BOL",
      "Botswana" -> "BWA",
      "Brazil" -> "BRA",
      "Burundi" -> "BDI",
      "Cape Verde" -> "CPV",
      "Chile" -> "CHL",
      "Colombia" -> "COL",
      "Costa Rica" -> "CRI",
      "Cuba" -> "CUB",
      "Democratic Republic of the Congo" -> "COD",
      "Dominican Republic" -> "DOM",
      "Ecuador" -> "ECU",
      "Egypt" -> "EGY",
      "Eritrea" -> "ERI",
      "Eswatini" -> "SWZ",
      "Ethiopia" -> "ETH",
      "French Guiana" -> "GUF",
      "Guyana" -> "GUY",
      "Haiti" -> "HTI",
      "India" -> "IND",
      "Indonesia" -> "IDN",
      "Kenya" -> "KEN",
      "Lesotho" -> "LSO",
      "Malawi" -> "MWI",
      "Maldives" -> "MDV",
      "Mongolia" -> "MNG",
      "Mozambique" -> "MOZ",
      "Myanmar" -> "MMR",
      "Namibia" -> "NAM",
      "Nepal" -> "NPL",
      "Oman" -> "OMN",
      "Pakistan" -> "PAK",
      "Panama" -> "PAN",
      "Paraguay" -> "PRY",
      "Peru" -> "PER",
      "Philippines" -> "PHL",
      "Qatar" -> "QAT",
      "Rwanda" -> "RWA",
      "Seychelles" -> "SYC",
      "Sierra Leone" -> "SLE",
      "Somalia" -> "SOM",
      "South Africa" -> "ZAF",
      "Sri Lanka" -> "LKA",
      "Sudan" -> "SDN",
      "Suriname" -> "SUR",
      "Tanzania" -> "TZA",
      "Tunisia" -> "TUN",
      "Turkey" -> "TUR",
      "Trinidad and Tobago" -> "TTO",
      "Uganda" -> "UGA",
      "United Arab Emirates" -> "ARE",
      "Uruguay" -> "URY",
      "Venezuela" -> "VEN",
      "Zambia" -> "ZMB",
      "Zimbabwe" -> "ZWE",
    )
}

sealed trait DirectRedListFlight {
  val isRedListOrigin: Boolean
  val terminalDiversion: Boolean
  val outgoingDiversion: Boolean
  val incomingDiversion: Boolean
  val paxDiversion: Boolean = outgoingDiversion || incomingDiversion
}

case class LhrDirectRedListFlight(isRedListOrigin: Boolean,
                                  terminalDiversion: Boolean,
                                  outgoingDiversion: Boolean,
                                  incomingDiversion: Boolean) extends DirectRedListFlight

case class DefaultDirectRedListFlight(isRedListOrigin: Boolean) extends DirectRedListFlight {
  override val terminalDiversion: Boolean = false
  override val outgoingDiversion: Boolean = false
  override val incomingDiversion: Boolean = false
}

object DirectRedListFlight {
  def apply(portCode: PortCode, displayTerminal: Terminal, flightTerminal: Terminal, isRedListOrigin: Boolean): DirectRedListFlight = {
    if (portCode == PortCode("LHR")) {
      val greenTerminal = List(T2, T3, T5).contains(displayTerminal)
      val terminalDiversion = displayTerminal != flightTerminal
      val outgoingDiversion = isRedListOrigin && greenTerminal
      val incomingDiversion = isRedListOrigin && terminalDiversion && !greenTerminal

      LhrDirectRedListFlight(isRedListOrigin, terminalDiversion, outgoingDiversion, incomingDiversion)
    } else
      DefaultDirectRedListFlight(isRedListOrigin)
  }
}

sealed trait IndirectRedListPax {
  val isEnabled: Boolean
}

object IndirectRedListPax {
  def apply(displayRedListInfo: Boolean,
            portCode: PortCode,
            flightWithSplits: ApiFlightWithSplits,
            maybePassengerInfo: Option[PassengerInfoSummary]): IndirectRedListPax =
    (displayRedListInfo, portCode) match {
      case (false, _) => NoIndirectRedListPax
      case (true, PortCode("LHR")) =>
        NeboIndirectRedListPax(flightWithSplits.apiFlight.RedListPax)
      case (true, _) =>
        val maybeNats = maybePassengerInfo.map(_.nationalities.filter {
          case (nat, _) => redListNats(flightWithSplits.apiFlight.Scheduled).toList.contains(nat)
        })
        ApiIndirectRedListPax(maybeNats)
    }

  def redListNats(date: MillisSinceEpoch): Iterable[Nationality] =
    RedList.countryCodesByName(date).values.map(Nationality(_))
}

sealed trait EnabledIndirectRedListPax extends IndirectRedListPax {
  override val isEnabled: Boolean = true
  val maybeCount: Option[Int]
}

case object NoIndirectRedListPax extends IndirectRedListPax {
  override val isEnabled: Boolean = false
}

case class ApiIndirectRedListPax(maybeNationalities: Option[Map[Nationality, Int]]) extends EnabledIndirectRedListPax {
  override val maybeCount: Option[Int] = maybeNationalities.map(_.values.sum)
}

case class NeboIndirectRedListPax(maybeCount: Option[Int]) extends EnabledIndirectRedListPax
