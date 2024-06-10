package drt.server.feeds.legacy.bhx

import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import uk.co.bhx.online.flightinformation.{FlightRecord, ScheduledFlightRecord}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDate

import javax.xml.datatype.XMLGregorianCalendar

sealed trait BHXArrivals {

  def convertToUTC(feedDate: XMLGregorianCalendar): Option[String] = {
    val date = feedDate.toGregorianCalendar.getTime

    date.getTime match {
      case 0L => None
      case _ =>
        val datetime = new DateTime(date.getTime).withMillisOfSecond(0).withSecondOfMinute(0)
        Some(datetime.withZone(DateTimeZone.UTC).toString(ISODateTimeFormat.dateTime))
    }
  }

  def convertToUTCPlusOneHour(feedDate: XMLGregorianCalendar): String = {
    val utcDatePlusOneHour = new DateTime(feedDate.toGregorianCalendar.getTimeInMillis, DateTimeZone.UTC)
      .plusHours(1)
      .withMillisOfSecond(0)
      .withSecondOfMinute(0)
    utcDatePlusOneHour.toString(ISODateTimeFormat.dateTime)
  }
}

trait BHXLiveArrivals extends BHXArrivals {

  def toLiveArrival(flightRecord: FlightRecord): LiveArrival = {
    val actPax = flightRecord.getPassengers
    val transPax = flightRecord.getTransits
    val (carrierCode, voyageNumber, suffix) = FlightCode.flightCodeToParts(flightRecord.getFlightNumber)

    LiveArrival(
      operator = None,
      maxPax = if (flightRecord.getCapacity == 0) None else Option(flightRecord.getCapacity),
      totalPax = if (actPax == 0) None else Option(actPax),
      transPax = if (transPax == 0) None else Option(transPax),
      terminal = Terminal(s"T${flightRecord.getTerminal}"),
      voyageNumber = voyageNumber.numeric,
      carrierCode = carrierCode.code,
      flightCodeSuffix = suffix.map(_.suffix),
      origin = flightRecord.getOrigin,
      scheduled = convertToUTC(flightRecord.getScheduledTime).map(SDate(_).millisSinceEpoch).getOrElse(0),
      estimated = convertToUTC(flightRecord.getEstimatedTime).map(SDate(_).millisSinceEpoch),
      touchdown = convertToUTC(flightRecord.getTouchdownTime).map(SDate(_).millisSinceEpoch),
      estimatedChox = convertToUTC(flightRecord.getEstimatedChoxTime).map(SDate(_).millisSinceEpoch),
      actualChox = convertToUTC(flightRecord.getChoxTime).map(SDate(_).millisSinceEpoch),
      status = flightRecord.getFlightStatus,
      gate = if (flightRecord.getGate.isBlank) None else Option(flightRecord.getGate),
      stand = if (flightRecord.getStand.isBlank) None else Option(flightRecord.getStand),
      runway = if (flightRecord.getRunway.isBlank) None else Option(flightRecord.getRunway),
      baggageReclaim = Option(flightRecord.getBelt),
    )
  }
}

trait BHXForecastArrivals extends BHXArrivals {

  def toForecastArrival(flightRecord: ScheduledFlightRecord): ForecastArrival = {
    val maxPax = flightRecord.getCapacity
    val actPax = flightRecord.getPassengers
    val transPax = flightRecord.getTransits
    val (carrierCode, voyageNumber, suffix) = FlightCode.flightCodeToParts(flightRecord.getFlightNumber)

    ForecastArrival(
      operator = None,
      maxPax = if (maxPax == 0) None else Option(maxPax),
      totalPax = Option(actPax),
      transPax = Option(transPax),
      terminal = Terminal(s"T${flightRecord.getTerminal}"),
      voyageNumber = voyageNumber.numeric,
      carrierCode = carrierCode.code,
      flightCodeSuffix = suffix.map(_.suffix),
      origin = flightRecord.getOrigin,
      scheduled = SDate(convertToUTCPlusOneHour(flightRecord.getScheduledTime)).millisSinceEpoch,
    )
  }
}
