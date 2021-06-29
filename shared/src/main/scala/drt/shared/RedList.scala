package drt.shared

import drt.shared.Terminals.{T2, T5, Terminal}
import drt.shared.api.PassengerInfoSummary

import scala.collection.immutable.Map

object RedList {
  def redListOriginWorkloadExcluded(portCode: PortCode, terminal: Terminal): Boolean =
    portCode == PortCode("LHR") && List(T2, T5).contains(terminal)

  val countryToCode: Map[String, String] = Map(
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
    "Democratic Republic of the Congo" -> "COD",
    "Ecuador" -> "ECU",
    "Egypt" -> "EGY",
    "Eswatini" -> "SWZ",
    "Ethiopia" -> "ETH",
    "French Guiana" -> "GUF",
    "Guyana" -> "GUY",
    "India" -> "IND",
    "Kenya" -> "KEN",
    "Lesotho" -> "LSO",
    "Malawi" -> "MWI",
    "Mozambique" -> "MOZ",
    "Namibia" -> "NAM",
    "Oman" -> "OMN",
    "Pakistan" -> "PAK",
    "Panama" -> "PAN",
    "Paraguay" -> "PRY",
    "Peru" -> "PER",
    "Philippines" -> "PHL",
    "Qatar" -> "QAT",
    "Rwanda" -> "RWA",
    "Seychelles" -> "SYC",
    "Somalia" -> "SOM",
    "South Africa" -> "ZAF",
    "Sri Lanka" -> "LKA",
    "Sudan" -> "SDN",
    "Suriname" -> "SUR",
    "Tanzania" -> "TZA",
    "Turkey" -> "TUR",
    "Trinidad and Tobago" -> "TTO",
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
    val greenTerminal = portCode == PortCode("LHR") && List(T2, T5).contains(displayTerminal)
    val terminalDiversion = displayTerminal != flightTerminal

    val outgoingDiversion =
      portCode == PortCode("LHR") && isRedListOrigin && greenTerminal
    val incomingDiversion =
      portCode == PortCode("LHR") && isRedListOrigin && terminalDiversion && !greenTerminal

    if (portCode == PortCode("LHR"))
      LhrDirectRedListFlight(isRedListOrigin, terminalDiversion, outgoingDiversion, incomingDiversion)
    else
      DefaultDirectRedListFlight(isRedListOrigin)
  }
}

sealed trait IndirectRedListPax {
  val isEnabled: Boolean
}

object IndirectRedListPax {
  def apply(displayRedListInfo: Boolean, portCode: PortCode, flightWithSplits: ApiFlightWithSplits, maybePassengerInfo: Option[PassengerInfoSummary]): IndirectRedListPax =
    (displayRedListInfo, portCode) match {
      case (false, _) => NoIndirectRedListPax
      case (true, PortCode("LHR")) =>
        NeboIndirectRedListPax(flightWithSplits.apiFlight.RedListPax)
      case (true, _) =>
        val maybeNats = maybePassengerInfo.map(_.nationalities.filter {
          case (nat, _) => redListNats.toList.contains(nat)
        })
        ApiIndirectRedListPax(maybeNats)
    }

  val redListNats: Iterable[Nationality] = RedList.countryToCode.values.map(Nationality(_))
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
