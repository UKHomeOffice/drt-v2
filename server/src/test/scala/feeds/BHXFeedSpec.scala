package feeds

import java.util.{Calendar, GregorianCalendar, TimeZone}
import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import drt.server.feeds.bhx.BHXFeed
import drt.shared.Arrival
import javax.xml.datatype.DatatypeFactory
import javax.xml.ws.BindingProvider
import org.mockito.Mockito.verify
import org.specs2.matcher.Scope
import org.specs2.mock.Mockito
import org.specs2.mutable.SpecificationLike
import uk.co.bhx.online.flightinformation._

import scala.collection.JavaConversions._

class BHXFeedSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.parseMap(
  Map("feeds.birmingham.soap.connection_timeout" -> 10,
    "feeds.birmingham.soap.receive_timeout" -> 30,
    "feeds.birmingham.soap.poll_frequency_in_minutes" -> 1,
    "feeds.birmingham.soap.initial_delay_in_milliseconds" -> 1,
    "feeds.birmingham.soap.endPointUrl" -> ""
  )))) with SpecificationLike with Mockito {
  sequential
  isolated

  trait WithScheduledFlightRecord{

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

  "Given a Birmingham feed exists" should {
    "we can read live flight data" in new Context {
      val feed = BHXFeed(serviceSoap)
      val arrivals: List[Arrival] = feed.getArrivals
      verify(serviceSoap).bfGetFlights
      arrivals.size mustEqual 1
      arrivals.head mustEqual new Arrival(
        Operator = "",
        Status = "A",
        EstDT = "2012-06-02T06:46:53.123Z",
        ActDT = "2012-06-02T06:46:53.123Z",
        EstChoxDT = "2012-06-02T06:46:53.123Z",
        ActChoxDT = "2012-06-02T06:46:53.123Z",
        Gate = "44",
        Stand = "57R",
        MaxPax = 80,
        ActPax = 40,
        TranPax = 35,
        RunwayID = "R1",
        BaggageReclaimId = "7A",
        FlightID = 0,
        AirportID = "BHX",
        Terminal = "1",
        rawICAO = "AF1164",
        rawIATA = "AF1164",
        Origin = "CPH",
        SchDT = "2012-06-02T06:46:53.123Z",
        Scheduled = 1338619613123L,
        PcpTime = 0,
        LastKnownPax = None)
    }

    "we can read forecast flight data" in new Context {
      val feed = BHXFeed(serviceSoap)
      val arrivals: List[Arrival] = feed.getForecastArrivals
      verify(serviceSoap).bfGetScheduledFlights()
      arrivals.size mustEqual 1
      arrivals.head mustEqual new Arrival(
        Operator = "",
        Status = "S",
        EstDT = "",
        ActDT = "",
        EstChoxDT = "",
        ActChoxDT = "",
        Gate = "",
        Stand = "",
        MaxPax = 80,
        ActPax = 40,
        TranPax = 35,
        RunwayID = "",
        BaggageReclaimId = "",
        FlightID = 0,
        AirportID = "BHX",
        Terminal = "1",
        rawICAO = "AF1164",
        rawIATA = "AF1164",
        Origin = "CPH",
        SchDT = "2012-06-02T06:46:53.123Z",
        Scheduled = 1338619613123L,
        PcpTime = 0,
        LastKnownPax = None)
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
      val arrivals: List[Arrival] = feed.getArrivals
      println(s"We got ${arrivals.size} Arrivals.")
      arrivals.foreach(println)
      ok
    }.pendingUntilFixed("used to test if the Birmingham feed is working locally given you can ssh into a whitelisted IP address")
  }

}