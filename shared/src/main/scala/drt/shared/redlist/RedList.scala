package drt.shared.redlist

import drt.shared.ApiFlightWithSplits
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.{T2, T3, T5, Terminal}
import uk.gov.homeoffice.drt.redlist.RedListUpdate

import scala.collection.immutable.{Map, SortedMap}


object RedList {
  def redListOriginWorkloadExcluded(portCode: PortCode, terminal: Terminal): Boolean =
    portCode == PortCode("LHR") && List(T2, T5).contains(terminal)

  val redListChanges: SortedMap[MillisSinceEpoch, RedListUpdate] = SortedMap(
    1613347200000L -> RedListUpdate(1613347200000L, Map( //15 feb
      "Angola" -> "AGO",
      "Argentina" -> "ARG",
      "Bolivia" -> "BOL",
      "Botswana" -> "BWA",
      "Brazil" -> "BRA",
      "Burundi" -> "BDI",
      "Cape Verde" -> "CPV",
      "Chile" -> "CHL",
      "Colombia" -> "COL",
      "Congo (Kinshasa)" -> "COD",
      "Ecuador" -> "ECU",
      "Eswatini" -> "SWZ",
      "French Guiana" -> "GUF",
      "Guyana" -> "GUY",
      "Lesotho" -> "LSO",
      "Malawi" -> "MWI",
      "Mauritius" -> "MUS",
      "Mozambique" -> "MOZ",
      "Namibia" -> "NAM",
      "Panama" -> "PAN",
      "Paraguay" -> "PRY",
      "Peru" -> "PER",
      "Portugal" -> "PRT",
      "Rwanda" -> "RWA",
      "Seychelles" -> "SYC",
      "South Africa" -> "ZAF",
      "Suriname" -> "SUR",
      "Tanzania" -> "TZA",
      "United Arab Emirates" -> "ARE",
      "Uruguay" -> "URY",
      "Venezuela" -> "VEN",
      "Zambia" -> "ZMB",
      "Zimbabwe" -> "ZWE",
    ), List()),
    1616112000000L -> RedListUpdate(1616112000000L, Map( //19 march
      "Ethiopia" -> "ETH",
      "Oman" -> "OMN",
      "Qatar" -> "QAT",
      "Somalia" -> "SOM",
    ), List("Portugal", "Mauritius")),
    1617922800000L -> RedListUpdate(1617922800000L, Map( // 9 april
      "Philippines" -> "PHL",
      "Pakistan" -> "PAK",
      "Kenya" -> "KEN",
      "Bangladesh" -> "BGD",
    ), List()),
    1619132400000L -> RedListUpdate(1619132400000L, Map( // 23 april
      "India" -> "IND"), List()),
    1620774000000L -> RedListUpdate(1620774000000L, Map( // 12 May
      "Turkey" -> "TUR",
      "Maldives" -> "MDV",
      "Nepal" -> "NPL",
    ), List()),
    1623106800000L -> RedListUpdate(1623106800000L, Map( // 8 June
      "Afghanistan" -> "AFG",
      "Bahrain" -> "BHR",
      "Costa Rica" -> "CRI",
      "Egypt" -> "EGY",
      "Sri Lanka" -> "LKA",
      "Sudan" -> "SDN",
      "Trinidad and Tobago" -> "TTO",
    ), List()),
    1625007600000L -> RedListUpdate(1625007600000L, Map( // 30 June
      "Dominican Republic" -> "DOM",
      "Eritrea" -> "ERI",
      "Haiti" -> "HTI",
      "Mongolia" -> "MNG",
      "Tunisia" -> "TUN",
      "Uganda" -> "UGA",
    ), List()),
    1626649200000L -> RedListUpdate(1626649200000L, Map( // 19 July
      "Cuba" -> "CUB",
      "Indonesia" -> "IDN",
      "Myanmar" -> "MMR",
      "Sierra Leone" -> "SLE",
    ), List()),
    1628377200000L -> RedListUpdate(1628377200000L, Map( // 8 Aug
      "Georgia" -> "GEO",
      "Mayotte" -> "MYT",
      "Mexico" -> "MEX",
      "Reunion" -> "REU",
    ), List(
      "Bahrain",
      "India",
      "Qatar",
      "United Arab Emirates",
    )),
    1630278000000L -> RedListUpdate(1630278000000L, Map( // 30 Aug
      "Thailand" -> "THA",
      "Montenegro" -> "MNE",
    ), List()),
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
  def apply(date: MillisSinceEpoch, portCode: PortCode, displayTerminal: Terminal, flightTerminal: Terminal, isRedListOrigin: Boolean): DirectRedListFlight = {
    if (portCode == PortCode("LHR") && date >= LhrRedListDatesImpl.t3RedListOpeningDate) {
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
  def apply(displayRedListInfo: Boolean, flightWithSplits: ApiFlightWithSplits): IndirectRedListPax =
    if (displayRedListInfo) NeboIndirectRedListPax(flightWithSplits.apiFlight.RedListPax) else NoIndirectRedListPax
}

sealed trait EnabledIndirectRedListPax extends IndirectRedListPax {
  override val isEnabled: Boolean = true
  val maybeCount: Option[Int]
}

case object NoIndirectRedListPax extends IndirectRedListPax {
  override val isEnabled: Boolean = false
}

case class NeboIndirectRedListPax(maybeCount: Option[Int]) extends EnabledIndirectRedListPax
