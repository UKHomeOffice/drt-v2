package server.feeds

object FlightFeeds {
  def standardiseFlightCode(flightCode: String): Option[String] = {
    val flightCodeRegex = "^([A-Z0-9]{2,3}?)([0-9]{1,4})([A-Z]?)$".r

    flightCode match {
      case flightCodeRegex(operator, flightNumber, suffix) =>
        Some(f"${operator}${flightNumber.toInt}%04d${suffix}")
      case _ => None
    }
  }
}