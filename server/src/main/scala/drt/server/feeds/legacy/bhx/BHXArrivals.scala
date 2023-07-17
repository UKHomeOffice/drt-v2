package drt.server.feeds.legacy.bhx

import drt.server.feeds.Implicits._
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import uk.co.bhx.online.flightinformation.{FlightRecord, ScheduledFlightRecord}
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers, Predictions}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ForecastFeedSource, LiveFeedSource, PortCode}
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

  protected def getPassengerSource(actPax: Int, transPax: Int) = {
    val actualPax = if (actPax == 0) None else Option(actPax)
    val transitPax = if (transPax == 0) None else Option(transPax)
    Passengers(actualPax, transitPax)
  }

}

trait BHXLiveArrivals extends BHXArrivals {

  def toLiveArrival(flightRecord: FlightRecord): Arrival = {
    val actPax = flightRecord.getPassengers
    val transPax = flightRecord.getTransits

    Arrival(
      Operator = None,
      Status = flightRecord.getFlightStatus,
      Predictions = Predictions(0L, Map()),
      Estimated = convertToUTC(flightRecord.getEstimatedTime).map(SDate(_).millisSinceEpoch),
      Actual = convertToUTC(flightRecord.getTouchdownTime).map(SDate(_).millisSinceEpoch),
      EstimatedChox = convertToUTC(flightRecord.getEstimatedChoxTime).map(SDate(_).millisSinceEpoch),
      ActualChox = convertToUTC(flightRecord.getChoxTime).map(SDate(_).millisSinceEpoch),
      Gate = if (flightRecord.getGate.isBlank) None else Option(flightRecord.getGate),
      Stand = if (flightRecord.getStand.isBlank) None else Option(flightRecord.getStand),
      MaxPax = if (flightRecord.getCapacity == 0) None else Option(flightRecord.getCapacity),
      RunwayID = if (flightRecord.getRunway.isBlank) None else Option(flightRecord.getRunway),
      BaggageReclaimId = Option(flightRecord.getBelt),
      AirportID = "BHX",
      Terminal = Terminal(s"T${flightRecord.getTerminal}"),
      rawICAO = flightRecord.getFlightNumber,
      rawIATA = flightRecord.getFlightNumber,
      Origin = PortCode(flightRecord.getOrigin),
      Scheduled = convertToUTC(flightRecord.getScheduledTime).map(SDate(_).millisSinceEpoch).getOrElse(0),
      PcpTime = None,
      FeedSources = Set(LiveFeedSource),
      PassengerSources = Map(LiveFeedSource -> getPassengerSource(actPax, transPax))
    )
  }
}

trait BHXForecastArrivals extends BHXArrivals {

  def toForecastArrival(flightRecord: ScheduledFlightRecord): Arrival = {
    val maxPax = flightRecord.getCapacity
    val actPax = flightRecord.getPassengers
    val transPax = flightRecord.getTransits
    Arrival(
      Operator = None,
      Status = "Port Forecast",
      Estimated = None,
      Predictions = Predictions(0L, Map()),
      Actual = None,
      EstimatedChox = None,
      ActualChox = None,
      Gate = None,
      Stand = None,
      MaxPax = if (maxPax == 0) None else Option(maxPax),
      RunwayID = None,
      BaggageReclaimId = None,
      AirportID = "BHX",
      Terminal = Terminal(s"T${flightRecord.getTerminal}"),
      rawICAO = flightRecord.getFlightNumber,
      rawIATA = flightRecord.getFlightNumber,
      Origin = flightRecord.getOrigin,
      Scheduled = SDate(convertToUTCPlusOneHour(flightRecord.getScheduledTime)).millisSinceEpoch,
      PcpTime = None,
      FeedSources = Set(ForecastFeedSource),
      PassengerSources = Map(ForecastFeedSource -> getPassengerSource(actPax, transPax))
    )
  }
}
