package feeds.bhx

import drt.server.feeds.legacy.bhx.BHXFeed
import jakarta.xml.ws.BindingProvider
import org.joda.time.DateTimeZone
import org.mockito.Mockito.verify
import org.specs2.matcher.Scope
import org.specs2.mock.Mockito
import uk.gov.homeoffice.drt.time.SDate
import services.crunch.CrunchTestLike
import uk.co.bhx.online.flightinformation._
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers, Predictions}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{ForecastFeedSource, LiveFeedSource, PortCode}

import java.util.{Calendar, GregorianCalendar, TimeZone}
import javax.xml.datatype.DatatypeFactory

class BHXLegacyFeedSpec extends CrunchTestLike with Mockito {
  sequential
  isolated

  trait WithScheduledFlightRecord {

    def getScheduledFlightRecord: ScheduledFlightRecord = {
      val gregorianCalendar = new GregorianCalendar
      gregorianCalendar.set(2012, 5, 2, 6, 46, 53)
      gregorianCalendar.set(Calendar.MILLISECOND, 123)
      gregorianCalendar.setTimeZone(TimeZone.getTimeZone("UTC"))
      val xmlGregorianCalendar = DatatypeFactory.newInstance.newXMLGregorianCalendar(gregorianCalendar)

      val scheduledFlightRecord = new ScheduledFlightRecord
      scheduledFlightRecord.setFlightNumber("AF1164")
      scheduledFlightRecord.setOrigin("CPH")
      scheduledFlightRecord.setScheduledTime(xmlGregorianCalendar)
      scheduledFlightRecord.setTerminal("1")
      scheduledFlightRecord.setPassengers(40)
      scheduledFlightRecord.setTransits(35)
      scheduledFlightRecord.setCapacity(80)
      scheduledFlightRecord

    }
  }

  trait WithLiveFlightRecord {
    def getFlightRecord: FlightRecord = {
      val gregorianCalendar = new GregorianCalendar
      gregorianCalendar.set(2012, 5, 2, 6, 46, 53)
      gregorianCalendar.set(Calendar.MILLISECOND, 123)
      gregorianCalendar.setTimeZone(TimeZone.getTimeZone("UTC"))
      val xmlGregorianCalendar = DatatypeFactory.newInstance.newXMLGregorianCalendar(gregorianCalendar)
      val flightRecord = new FlightRecord
      flightRecord.setFlightStatus("Arrived")
      flightRecord.setFlightNumber("AF1164")
      flightRecord.setOrigin("CPH")
      flightRecord.setScheduledTime(xmlGregorianCalendar)
      flightRecord.setTerminal("1")
      flightRecord.setEstimatedTime(xmlGregorianCalendar)
      flightRecord.setTouchdownTime(xmlGregorianCalendar)
      flightRecord.setChoxTime(xmlGregorianCalendar)
      flightRecord.setEstimatedChoxTime(xmlGregorianCalendar)
      flightRecord.setPassengers(40)
      flightRecord.setTransits(35)
      flightRecord.setCapacity(80)
      flightRecord.setGate("44")
      flightRecord.setStand("57R")
      flightRecord.setBelt("7A")
      flightRecord.setRunway("R1")
      flightRecord
    }
  }

  trait Context extends Scope with WithLiveFlightRecord with WithScheduledFlightRecord {

    val serviceSoap: FlightInformationSoap = mock[FlightInformationSoap]

    val arrayOfFlightRecord = new ArrayOfFlightRecord
    arrayOfFlightRecord.getFlightRecord.add(getFlightRecord)
    serviceSoap.bfGetFlights returns arrayOfFlightRecord

    val arrayOfScheduledFlightRecords = new ArrayOfScheduledFlightRecord
    arrayOfScheduledFlightRecords.getScheduledFlightRecord.add(getScheduledFlightRecord)
    serviceSoap.bfGetScheduledFlights() returns arrayOfScheduledFlightRecords
  }

  "Given a BHX feed exists" should {
    "we can read live flight data" in new Context {
      val feed: BHXFeed = BHXFeed(serviceSoap)
      val arrivals: List[Arrival] = feed.getLiveArrivals
      verify(serviceSoap).bfGetFlights
      arrivals.size mustEqual 1

      import drt.server.feeds.Implicits._

      arrivals.head mustEqual Arrival(
        Operator = None,
        Status = "Arrived",
        Estimated = Some(SDate("2012-06-02T06:46:00Z", DateTimeZone.UTC).millisSinceEpoch),
        Predictions = Predictions(0L, Map()),
        Actual = Some(SDate("2012-06-02T06:46:00Z", DateTimeZone.UTC).millisSinceEpoch),
        EstimatedChox = Some(SDate("2012-06-02T06:46:00Z", DateTimeZone.UTC).millisSinceEpoch),
        ActualChox = Some(SDate("2012-06-02T06:46:00Z", DateTimeZone.UTC).millisSinceEpoch),
        Gate = Some("44"),
        Stand = Some("57R"),
        MaxPax = Some(80),
        RunwayID = Some("R1"),
        BaggageReclaimId = Some("7A"),
        AirportID = PortCode("BHX"),
        Terminal = T1,
        rawICAO = "AF1164",
        rawIATA = "AF1164",
        Origin = PortCode("CPH"),
        Scheduled = 1338619560000L,
        PcpTime = None,
        FeedSources = Set(LiveFeedSource),
        PassengerSources = Map(LiveFeedSource -> Passengers(Some(40), Some(35)))
      )
    }

    "we can read forecast flight data with seconds dropped from timestamps" in new Context {
      val feed: BHXFeed = BHXFeed(serviceSoap)
      val arrivals: List[Arrival] = feed.getForecastArrivals
      verify(serviceSoap).bfGetScheduledFlights()
      arrivals.size mustEqual 1

      import drt.server.feeds.Implicits._

      arrivals.head mustEqual Arrival(
        Operator = None,
        Status = "Port Forecast",
        Estimated = None,
        Predictions = Predictions(0L, Map()),
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = Some(80),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("BHX"),
        Terminal = T1,
        rawICAO = "AF1164",
        rawIATA = "AF1164",
        Origin = PortCode("CPH"),
        Scheduled = 1338623160000L, // BHX Forecast is incorrect. This should be 1338619613123L or 2012-06-02T06:46:53.123Z
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource),
        PassengerSources = Map(ForecastFeedSource -> Passengers(Some(40), Some(35)))
      )
    }

    "an exploratory test" in {
      skipped("exploratory test for the BHX live feed")
      val f = new FlightInformation(this.getClass.getClassLoader.getResource("FlightInformation.wsdl"))
      val soapService =
        f.getFlightInformationSoap match {
          case binder: BindingProvider =>
            val endpointURL = "https://online.example.co.uk:4443/flightinformationservice/FlightInformation.asmx"
            binder.getRequestContext.put("javax.xml.ws.client.connectionTimeout", "300000")
            binder.getRequestContext.put("javax.xml.ws.client.receiveTimeout", "300000")
            binder.getRequestContext.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endpointURL)
            binder
          case flightInformationSoap => flightInformationSoap
        }
      val feed = BHXFeed(soapService)
      val arrivals: List[Arrival] = feed.getLiveArrivals
      ok
    }.pendingUntilFixed("used to test if the BHX feed is working locally given you can ssh into a whitelisted IP address")
  }

}
