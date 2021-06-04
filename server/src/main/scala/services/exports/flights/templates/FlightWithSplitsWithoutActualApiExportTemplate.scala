package services.exports.flights.templates

import org.joda.time.DateTimeZone

case class FlightWithSplitsWithoutActualApiExportTemplate(override val timeZone: DateTimeZone) extends FlightWithSplitsExportTemplate
