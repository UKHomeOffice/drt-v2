package drt.client.util

object AirportName {

  private val AirportNameIndex: Map[String, String] = Map(
    "BHX" -> "Birmingham",
    "EMA" -> "East Midlands",
    "LCY" -> "London City",
    "SEN" -> "Southend",
    "STN" -> "Stansted",
    "LGW" -> "Gatwick",
    "LTN" -> "Luton",
    "NWI" -> "Norwich",
    "ABZ" -> "Aberdeen",
    "BFS" -> "Belfast",
    "BHD" -> "Belfast City",
    "EDI" -> "Edinburgh",
    "BRS" -> "Bristol",
    "GLA" -> "Glasgow",
    "PIK" -> "Prestwick",
    "HUY" -> "Humberside",
    "INV" -> "Inverness",
    "LBA" -> "Leeds Bradford",
    "LPL" -> "Liverpool",
    "MAN" -> "Manchester",
    "MME" -> "Teeside",
    "NCL" -> "Newcastle",
    "BOH" -> "Bournemouth",
    "CWL" -> "Cardiff",
    "EXT" -> "Exeter",
    "NQY" -> "Newquay",
    "SOU" -> "Southampton",
    "LHR" -> "Heathrow",
    "LHR-T2" -> "Heathrow T2",
    "LHR-T3" -> "Heathrow T3",
    "LHR-T4" -> "Heathrow T4",
    "LHR-T5" -> "Heathrow T5"
  )

  def getAirportByCode(portCode: String): Option[String] =
    AirportNameIndex.get(portCode)

}
