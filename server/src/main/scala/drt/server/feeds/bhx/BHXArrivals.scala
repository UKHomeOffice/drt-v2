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

  def toLiveArrival(flightRecord: FlightRecord): Arrival = {
    val actPax = flightRecord.getPassengers
    val transPax = flightRecord.getTransits
    new Arrival(Operator = None,
      Status = flightRecord.getFlightStatus,
      Estimated = convertToUTC(flightRecord.getEstimatedTime).map(SDate(_).millisSinceEpoch),
      Actual = convertToUTC(flightRecord.getTouchdownTime).map(SDate(_).millisSinceEpoch),
      EstimatedChox = convertToUTC(flightRecord.getEstimatedChoxTime).map(SDate(_).millisSinceEpoch),
      ActualChox = convertToUTC(flightRecord.getChoxTime).map(SDate(_).millisSinceEpoch),
      Gate = Some(flightRecord.getGate),
      Stand = Some(flightRecord.getStand),
      MaxPax = Some(flightRecord.getCapacity),
      ActPax = if (actPax == 0) None else Some(actPax),
      TranPax = if (actPax == 0) None else Some(transPax),
      RunwayID = Some(flightRecord.getRunway),
      BaggageReclaimId = Some(flightRecord.getBelt),
      FlightID = None,
      AirportID = "BHX",
      Terminal = s"T${flightRecord.getTerminal}",
      rawICAO = flightRecord.getFlightNumber,
      rawIATA = flightRecord.getFlightNumber,
      Origin = flightRecord.getOrigin,
      Scheduled = convertToUTC(flightRecord.getScheduledTime).map(SDate(_).millisSinceEpoch).getOrElse(0),
      PcpTime = None,
      None)
  }
}

trait BHXForecastArrivals extends BHXArrivals {

  def toForecastArrival(flightRecord: ScheduledFlightRecord) : Arrival = {
    val maxPax = flightRecord.getCapacity
    val actPax = flightRecord.getPassengers
    val transPax = flightRecord.getTransits
    new Arrival(Operator = None,
      Status = "Port Forecast",
      Estimated = None,
      Actual = None,
      EstimatedChox = None,
      ActualChox = None,
      Gate = None,
      Stand = None,
      MaxPax = if (maxPax == 0) None else Some(maxPax),
      ActPax = if (actPax==0) None else Some(actPax),
      TranPax = if (actPax==0) None else Some(transPax),
      RunwayID = None,
      BaggageReclaimId = None,
      FlightID = None,
      AirportID = "BHX",
      Terminal = s"T${flightRecord.getTerminal}",
      rawICAO = flightRecord.getFlightNumber,
      rawIATA = flightRecord.getFlightNumber,
      Origin = flightRecord.getOrigin,
      Scheduled = SDate(convertToUTCPlusOneHour(flightRecord.getScheduledTime)).millisSinceEpoch,
      PcpTime = None,
      None)
  }
}