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
import uk.gov.homeoffice.drt.arrivals.{Arrival, FlightCode, ForecastArrival, LiveArrival, Passengers, Predictions}
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
      val arrivals = feed.getLiveArrivals
      verify(serviceSoap).bfGetFlights
      arrivals.size mustEqual 1

      val (carrierCode, voyageNumber, suffix) = FlightCode.flightCodeToParts("AF1164")

      arrivals.head mustEqual LiveArrival(
        operator = None,
        maxPax = Some(80),
        totalPax = Some(40),
        transPax = Some(35),
        terminal = T1,
        voyageNumber = voyageNumber.numeric,
        carrierCode = carrierCode.code,
        flightCodeSuffix = suffix.map(_.suffix),
        origin = "CPH",
        scheduled = 1338619560000L,
        estimated = Some(SDate("2012-06-02T06:46:00Z", DateTimeZone.UTC).millisSinceEpoch),
        touchdown = Some(SDate("2012-06-02T06:46:00Z", DateTimeZone.UTC).millisSinceEpoch),
        estimatedChox = Some(SDate("2012-06-02T06:46:00Z", DateTimeZone.UTC).millisSinceEpoch),
        actualChox = Some(SDate("2012-06-02T06:46:00Z", DateTimeZone.UTC).millisSinceEpoch),
        status = "Arrived",
        gate = Some("44"),
        stand = Some("57R"),
        runway = Some("R1"),
        baggageReclaim = Some("7A"),
      )
    }

    "we can read forecast flight data with seconds dropped from timestamps" in new Context {
      val feed: BHXFeed = BHXFeed(serviceSoap)
      val arrivals = feed.getForecastArrivals
      verify(serviceSoap).bfGetScheduledFlights()
      arrivals.size mustEqual 1

      val (carrierCode, voyageNumber, suffix) = FlightCode.flightCodeToParts("AF1164")

      arrivals.head mustEqual ForecastArrival(
        operator = None,
        maxPax = Some(80),
        totalPax = Some(40),
        transPax = Some(35),
        terminal = T1,
        voyageNumber = voyageNumber.numeric,
        carrierCode = carrierCode.code,
        flightCodeSuffix = suffix.map(_.suffix),
        origin = "CPH",
        scheduled = 1338623160000L,
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
      feed.getLiveArrivals
      ok
    }.pendingUntilFixed("used to test if the BHX feed is working locally given you can ssh into a whitelisted IP address")
  }

}
