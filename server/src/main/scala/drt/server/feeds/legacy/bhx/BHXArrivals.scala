package drt.server.feeds.legacy.bhx

import drt.shared.{Arrival, FeedSource, ForecastFeedSource, LiveFeedSource}
import javax.xml.datatype.XMLGregorianCalendar
import org.joda.time.DateTime
import services.SDate
import uk.co.bhx.online.flightinformation.{FlightRecord, ScheduledFlightRecord}
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import org.springframework.util.StringUtils

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

  def toLiveArrival(flightRecord: FlightRecord): Arrival = {
    val actPax = flightRecord.getPassengers
    val transPax = flightRecord.getTransits
    new Arrival(Operator = None,
      Status = flightRecord.getFlightStatus,
      Estimated = convertToUTC(flightRecord.getEstimatedTime).map(SDate(_).millisSinceEpoch),
      Actual = convertToUTC(flightRecord.getTouchdownTime).map(SDate(_).millisSinceEpoch),
      EstimatedChox = convertToUTC(flightRecord.getEstimatedChoxTime).map(SDate(_).millisSinceEpoch),
      ActualChox = convertToUTC(flightRecord.getChoxTime).map(SDate(_).millisSinceEpoch),
      Gate = if (StringUtils.isEmpty(flightRecord.getGate)) None else Option(flightRecord.getGate),
      Stand = if (StringUtils.isEmpty(flightRecord.getStand)) None else Option(flightRecord.getStand),
      MaxPax = if (flightRecord.getCapacity == 0) None else Option(flightRecord.getCapacity),
      ActPax = if (actPax == 0) None else Option(actPax),
      TranPax = if (actPax == 0) None else Option(transPax),
      RunwayID = if (StringUtils.isEmpty(flightRecord.getRunway)) None else Option(flightRecord.getRunway),
      BaggageReclaimId = Option(flightRecord.getBelt),
      FlightID = None,
      AirportID = "BHX",
      Terminal = s"T${flightRecord.getTerminal}",
      rawICAO = flightRecord.getFlightNumber,
      rawIATA = flightRecord.getFlightNumber,
      Origin = flightRecord.getOrigin,
      Scheduled = convertToUTC(flightRecord.getScheduledTime).map(SDate(_).millisSinceEpoch).getOrElse(0),
      PcpTime = None,
      FeedSources = Set(LiveFeedSource),
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
      MaxPax = if (maxPax == 0) None else Option(maxPax),
      ActPax = if (actPax==0) None else Option(actPax),
      TranPax = if (actPax==0) None else Option(transPax),
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
      FeedSources = Set(ForecastFeedSource),
      None)
  }
}
