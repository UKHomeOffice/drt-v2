package drt.shared

import drt.shared.Terminals.{T2, T5, Terminal}

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

sealed trait RedListInfo {
  val isRedListOrigin: Boolean
  val terminalDiversion: Boolean
  val outgoingDiversion: Boolean
  val incomingDiversion: Boolean
}

case class LhrRedListInfo(isRedListOrigin: Boolean, terminalDiversion: Boolean, outgoingDiversion: Boolean, incomingDiversion: Boolean) extends RedListInfo

case class DefaultRedListInfo(isRedListOrigin: Boolean) extends RedListInfo {
  override val terminalDiversion: Boolean = false
  override val outgoingDiversion: Boolean = false
  override val incomingDiversion: Boolean = false
}

object RedListInfo {
  def apply(portCode: PortCode, displayTerminal: Terminal, flightTerminal: Terminal, isRedListOrigin: Boolean): RedListInfo = {
    val greenTerminal = portCode == PortCode("LHR") && List(T2, T5).contains(displayTerminal)
    val terminalDiversion = displayTerminal != flightTerminal

    val outgoingDiversion =
      portCode == PortCode("LHR") && isRedListOrigin && greenTerminal
    val incomingDiversion =
      portCode == PortCode("LHR") && isRedListOrigin && terminalDiversion && !greenTerminal

    if (portCode == PortCode("LHR"))
      LhrRedListInfo(isRedListOrigin, terminalDiversion, outgoingDiversion, incomingDiversion)
    else
      DefaultRedListInfo(isRedListOrigin)
  }
}
