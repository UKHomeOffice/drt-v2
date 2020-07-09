package drt.server.feeds.common

object FlightStatus {
  val statusCodes = Map(
    "AFC" -> "AF CHECK-IN OPEN",
    "AIR" -> "AIRBORNE",
    "ARR" -> "ARRIVED ON STAND",
    "BBR" -> "BOARDING BY ROW",
    "BBZ" -> "BOARDING BY ZONE",
    "BDG" -> "BOARDING",
    "BOB" -> "BAGGAGE IN HALL",
    "CAN" -> "CANCELLED",
    "CIC" -> "CHECK-IN CLOSED",
    "CIO" -> "CHECK-IN OPEN",
    "DEP" -> "DEPARTED (PUSH BACK)",
    "DIV" -> "DIVERTED",
    "DLO" -> "DELAYED OPERATIONAL",
    "EGC" -> "GATE CLOSED",
    "ETD" -> "ESTIMATED TIME DEPARTURE",
    "EXP" -> "EXPECTED",
    "EZS" -> "EZS CHECK-IN OPEN",
    "EZY" -> "EZY CHECK-IN OPEN",
    "FCL" -> "FINAL CALL",
    "FTJ" -> "FAILED TO JOIN",
    "GTO" -> "GATE OPEN",
    "IND" -> "INDEFINTE DELAY",
    "LBB" -> "LAST BAG DELIVERED",
    "LND" -> "LANDED",
    "MDB" -> "THOMAS COOK DAY BEFORE CHECK IN",
    "NXT" -> "NEXT INFORMATION AT",
    "ONA" -> "ON APPROACH",
    "RSH" -> "RESCHEDULED",
    "STA" -> "SCHEDULED TIME OF ARRIVAL",
    "TDB" -> "THOMSON DAY BEFORE CHECK IN",
    "TOM" -> "TOM CHECK-IN OPEN",
    "WAI" -> "WAIT IN LOUNGE"
  )

  def apply(code: String): String = statusCodes.getOrElse(code, code)
}