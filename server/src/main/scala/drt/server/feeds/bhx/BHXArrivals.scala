package drt.server.feeds.bhx

import drt.shared.Arrival
import javax.xml.datatype.XMLGregorianCalendar
import org.joda.time.DateTime
import services.SDate
import uk.co.bhx.online.flightinformation.{FlightRecord, ScheduledFlightRecord}
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat

sealed trait BHXArrivals {

  def convertToUTC(feedDate: XMLGregorianCalendar): Option[String] = {
    val date = feedDate.toGregorianCalendar.getTime

    date.getTime match {
      case 0L => None
      case _ =>
        val datetime = new DateTime(date.getTime)
        Some(datetime.withZone(DateTimeZone.UTC).toString(ISODateTimeFormat.dateTime))
    }
  }

  def convertToUTCPlusOneHour(feedDate: XMLGregorianCalendar): String = {
    val utcDatePlusOneHour = new DateTime(feedDate.toGregorianCalendar.getTimeInMillis, DateTimeZone.UTC).plusHours(1)
    utcDatePlusOneHour.toString(ISODateTimeFormat.dateTime)
  }

}

trait BHXLiveArrivals extends BHXArrivals {

  def toLiveArrival(flightRecord: FlightRecord): Arrival =
    new Arrival(Operator = "",
      Status = flightRecord.getFlightStatus,
      Estimated = convertToUTC(flightRecord.getEstimatedTime).map(SDate(_).millisSinceEpoch).getOrElse(0),
      Actual = convertToUTC(flightRecord.getTouchdownTime).map(SDate(_).millisSinceEpoch).getOrElse(0),
      EstimatedChox = convertToUTC(flightRecord.getEstimatedChoxTime).map(SDate(_).millisSinceEpoch).getOrElse(0),
      ActualChox = convertToUTC(flightRecord.getChoxTime).map(SDate(_).millisSinceEpoch).getOrElse(0),
      Gate = flightRecord.getGate,
      Stand = flightRecord.getStand,
      MaxPax = flightRecord.getCapacity,
      ActPax = flightRecord.getPassengers,
      TranPax = flightRecord.getTransits,
      RunwayID = flightRecord.getRunway,
      BaggageReclaimId = flightRecord.getBelt,
      FlightID = 0,
      AirportID = "BHX",
      Terminal = s"T${flightRecord.getTerminal}",
      rawICAO = flightRecord.getFlightNumber,
      rawIATA = flightRecord.getFlightNumber,
      Origin = flightRecord.getOrigin,
      Scheduled = convertToUTC(flightRecord.getScheduledTime).map(SDate(_).millisSinceEpoch).getOrElse(0),
      PcpTime = 0,
      None)
}

trait BHXForecastArrivals extends BHXArrivals {

  def toForecastArrival(flightRecord: ScheduledFlightRecord) : Arrival =
    new Arrival(Operator = "",
      Status = "Port Forecast",
      Estimated = 0L,
      Actual = 0L,
      EstimatedChox = 0L,
      ActualChox = 0L,
      Gate = "",
      Stand = "",
      MaxPax = flightRecord.getCapacity,
      ActPax = flightRecord.getPassengers,
      TranPax = flightRecord.getTransits,
      RunwayID = "",
      BaggageReclaimId = "",
      FlightID = 0,
      AirportID = "BHX",
      Terminal = s"T${flightRecord.getTerminal}",
      rawICAO = flightRecord.getFlightNumber,
      rawIATA = flightRecord.getFlightNumber,
      Origin = flightRecord.getOrigin,
      Scheduled = SDate(convertToUTCPlusOneHour(flightRecord.getScheduledTime)).millisSinceEpoch,
      PcpTime = 0,
      None)
}