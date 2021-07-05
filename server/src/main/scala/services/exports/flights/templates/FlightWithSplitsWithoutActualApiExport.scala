package services.exports.flights.templates

import org.joda.time.DateTimeZone

case class FlightWithSplitsWithoutActualApiExport(override val timeZone: DateTimeZone) extends FlightWithSplitsExport
