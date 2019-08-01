package feeds

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import drt.server.feeds.bhx.{BHXClient, BHXFeed, BHXFlight}
import drt.shared.{Arrival, LiveFeedSource}
import org.specs2.mutable.SpecificationLike
import services.SDate

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.xml.XML


class BHXFeedSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.empty())) with SpecificationLike {
  sequential
  isolated

  implicit val materializer = ActorMaterializer()

  "The BHX Feed client should successfully get a response from the BHX server" >> {

    skipped("Exploratory test - requires VPN connection, correct feed url and username env vars")

    val endpoint = sys.env("BHX_IATA_ENDPOINT_URL")
    val username = sys.env("BHX_IATA_USERNAME")

    val bhxClient = BHXClient(username, endpoint)
    println(Await.result(bhxClient.flights(system), 30 seconds))

    false
  }

  "Given some flight xml with 1 flight, I should get get back a list of 1 arrival" >> {

    val resp = HttpResponse(
      entity = HttpEntity(
        contentType = ContentType(MediaTypes.`application/xml`, HttpCharsets.`UTF-8`),
        bhxSoapResponse1FlightXml
      )
    )

    val result = Await.result(Unmarshal[HttpResponse](resp).to[List[BHXFlight]], 5 seconds)
    val expected = List(
      BHXFlight(
        "TOM",
        "7623",
        "PFO",
        "BHX",
        "1",
        "ARR",
        "2018-09-01T23:00:00.000Z",
        arrival = true,
        international = true,
        None,
        Option("2018-09-01T23:05:00.000Z"),
        None,
        Option("2018-09-01T23:00:00.000Z"),
        Option("54L"),
        Option("44"),
        Option(189),
        Option(65)
      )
    )
    println(result)
    println(expected)
    result === expected
  }

  "Given some flight xml with 2 flights, I should get get back 2 arrival objects" >> {

    val resp = HttpResponse(
      entity = HttpEntity(
        contentType = ContentType(MediaTypes.`application/xml`, HttpCharsets.`UTF-8`),
        bhxSoapResponse2FlightsXml
      )
    )

    val result = Await.result(Unmarshal[HttpResponse](resp).to[List[BHXFlight]], 5 seconds)
    val expected = List(
      BHXFlight(
        "TOM",
        "7623",
        "PFO",
        "BHX",
        "1",
        "ARR",
        "2018-09-01T23:00:00.000Z",
        arrival = true,
        international = true,
        None,
        Option("2018-09-01T23:05:00.000Z"),
        None,
        Option("2018-09-01T23:00:00.000Z"),
        Option("54L"),
        Option("44"),
        Option(189)

      ),
      BHXFlight(
        "FR",
        "8045",
        "CHQ",
        "BHX",
        "2",
        "ARR",
        "2018-09-18T23:00:00.000Z",
        arrival = true,
        international = true,
        None,
        Option("2018-09-18T23:05:00.000Z"),
        None,
        Option("2018-09-18T23:00:00.000Z"),
        Option("1"),
        Option("1"),
        Option(189)
      )
    )

    result === expected
  }

  "Given a list of operation times I should be able to extract the scheduled time" >> {
    val xml =
      XML.loadString(
        """
          |<LegData>
          |   <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="SCT">2018-09-01T23:00:00.000Z</OperationTime>
          |   <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="ACT">2018-09-01T23:00:00.000Z</OperationTime>
          |</LegData>
        """.stripMargin)

    val expected = "2018-09-01T23:00:00.000Z"
    val node = xml \ "OperationTime"
    val result = BHXFlight.scheduledTime(node).get

    result === expected
  }

  "Given a list of operation times I should be able to extract the actual chox time" >> {
    val xml =
      XML.loadString(
        """
          |<LegData>
          |   <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="SCT">2018-09-01T23:00:00.000Z</OperationTime>
          |   <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="ACT">2018-09-01T24:00:00.000Z</OperationTime>
          |</LegData>
        """.stripMargin)


    val expected = "2018-09-01T24:00:00.000Z"
    val node = xml \ "OperationTime"
    val result = BHXFlight.actualChox(node).get

    result === expected
  }

  "Given a list of operation times I should be able to extract the estimated chox time" >> {
    val xml =
      XML.loadString(
        """
          |<LegData>
          |   <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="SCT">2018-09-01T23:00:00.000Z</OperationTime>
          |   <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="EST">2018-09-01T24:00:00.000Z</OperationTime>
          |</LegData>
        """.stripMargin)


    val expected = "2018-09-01T24:00:00.000Z"
    val node = xml \ "OperationTime"
    val result = BHXFlight.estChox(node).get

    result === expected
  }

  "Given a list of operation times I should be able to extract the estimated touchdown time" >> {
    val xml =
      XML.loadString(
        """
          |<LegData>
          |   <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="SCT">2018-09-01T23:00:00.000Z</OperationTime>
          |   <OperationTime OperationQualifier="TDN" CodeContext="2005" TimeType="EST">2018-09-01T24:00:00.000Z</OperationTime>
          |</LegData>
        """.stripMargin)


    val expected = "2018-09-01T24:00:00.000Z"
    val node = xml \ "OperationTime"
    val result = BHXFlight.estTouchDown(node).get

    result === expected
  }

  "Given a list of operation times I should be able to extract the actual touchdown time" >> {
    val xml =
      XML.loadString(
        """
          |<LegData>
          |   <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="SCT">2018-09-01T23:00:00.000Z</OperationTime>
          |   <OperationTime OperationQualifier="TDN" CodeContext="2005" TimeType="ACT">2018-09-01T24:00:00.000Z</OperationTime>
          |</LegData>
        """.stripMargin)


    val expected = "2018-09-01T24:00:00.000Z"
    val node = xml \ "OperationTime"
    val result = BHXFlight.actualTouchDown(node).get

    result === expected
  }

  "Given a BHXFlight, I should get an Arrival back with the same fields" >> {
    val estimatedOnBlocksTimeString = "2018-09-01T23:05:00.000Z"
    val actualOnBlocksTimeString = "2018-09-01T23:06:00.000Z"
    val estimatedTouchDownTimeString = "2018-09-01T23:07:00.000Z"
    val actualTouchDownTimeString = "2018-09-01T23:08:00.000Z"
    val scheduledTimeString = "2018-09-01T23:00:00.000Z"

    val bhxFlight = BHXFlight(
      "SA",
      "123",
      "JNB",
      "BHX",
      "1",
      "ARR",
      scheduledTimeString,
      true,
      true,
      Option(estimatedOnBlocksTimeString),
      Option(actualOnBlocksTimeString),
      Option(estimatedTouchDownTimeString),
      Option(actualTouchDownTimeString),
      Option("55"),
      Option("6"),
      Option(175),
      Option(65),
      Nil
    )

    val result = BHXFeed.bhxFlightToArrival(bhxFlight)

    val expected = Arrival(
      Option("SA"),
      "ARRIVED ON STAND",
      Option(SDate(estimatedTouchDownTimeString).millisSinceEpoch),
      Option(SDate(actualTouchDownTimeString).millisSinceEpoch),
      Option(SDate(estimatedOnBlocksTimeString).millisSinceEpoch),
      Option(SDate(actualOnBlocksTimeString).millisSinceEpoch),
      Option("6"),
      Option("55"),
      Option(175),
      Option(65),
      None,
      None,
      None,
      None,
      "BHX",
      "T1",
      "SA123",
      "SA123",
      "JNB",
      SDate(scheduledTimeString).millisSinceEpoch,
      None,
      Set(LiveFeedSource),
      None
    )

    result === expected
  }

  val bhxSoapResponse1FlightXml =
    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
      |    <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      |        <IATA_AIDX_FlightLegRS TimeStamp="2019-07-25T09:13:19.4014748+01:00" Version="16.1" xmlns="http://www.iata.org/IATA/2007/00">
      |            <Success/>
      |            <FlightLeg>
      |                <LegIdentifier>
      |                    <Airline CodeContext="3">TOM</Airline>
      |                    <FlightNumber>7623</FlightNumber>
      |                    <DepartureAirport CodeContext="3">PFO</DepartureAirport>
      |                    <ArrivalAirport CodeContext="3">BHX</ArrivalAirport>
      |                    <OriginDate>2018-09-01</OriginDate>
      |                </LegIdentifier>
      |                <LegData InternationalStatus="International">
      |                    <PublicStatus xsi:nil="true"/>
      |                    <OperatingAlliance xsi:nil="true"/>
      |                    <ServiceType>C</ServiceType>
      |                    <EstFlightDuration xsi:nil="true"/>
      |                    <OwnerAirline xsi:nil="true"/>
      |                    <CabinClass Class="7">
      |                        <SeatCapacity>189</SeatCapacity>
      |                        <PaxCount Qualifier="A" Usage="Planned" DestinationType="Local">65</PaxCount>
      |                    </CabinClass>
      |                    <RemarkFreeText>ARR</RemarkFreeText>
      |                    <AirportResources Usage="Planned">
      |                        <Resource DepartureOrArrival="Arrival">
      |                            <AirportZone xsi:nil="true"/>
      |                            <AircraftParkingPosition>54L</AircraftParkingPosition>
      |                            <PassengerGate>44</PassengerGate>
      |                            <Runway xsi:nil="true"/>
      |                            <AircraftTerminal>1</AircraftTerminal>
      |                            <BaggageClaimUnit>3</BaggageClaimUnit>
      |                            <DeIceLocation xsi:nil="true"/>
      |                        </Resource>
      |                    </AirportResources>
      |                    <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="SCT">2018-09-01T23:00:00.000Z</OperationTime>
      |                    <OperationTime OperationQualifier="TDN" CodeContext="2005" TimeType="ACT">2018-09-01T23:00:00.000Z</OperationTime>
      |                    <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="ACT">2018-09-01T23:05:00.000Z</OperationTime>
      |                    <AircraftInfo>
      |                        <AircraftType>73H</AircraftType>
      |                        <AircraftSubType xsi:nil="true"/>
      |                        <Registration xsi:nil="true"/>
      |                        <TailNumber xsi:nil="true"/>
      |                        <AgentInfo DepartureOrArrival="Arrival">S</AgentInfo>
      |                        <FleetNumber xsi:nil="true"/>
      |                        <CallSign xsi:nil="true"/>
      |                    </AircraftInfo>
      |                </LegData>
      |                <TPA_Extension>
      |                    <TPA_KeyValue Key="AirlineName">Thomson Airways</TPA_KeyValue>
      |                    <TPA_KeyValue Key="DepartureAirportName">Paphos</TPA_KeyValue>
      |                    <TPA_KeyValue Key="ArrivalAirportName">Birmingham</TPA_KeyValue>
      |                </TPA_Extension>
      |            </FlightLeg>
      |        </IATA_AIDX_FlightLegRS>
      |    </s:Body>
      |</s:Envelope>
    """.stripMargin

  val bhxSoapResponse2FlightsXml =
    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
      |    <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      |        <IATA_AIDX_FlightLegRS TimeStamp="2019-07-25T09:13:19.4014748+01:00" Version="16.1" xmlns="http://www.iata.org/IATA/2007/00">
      |            <Success/>
      |            <FlightLeg>
      |                <LegIdentifier>
      |                    <Airline CodeContext="3">TOM</Airline>
      |                    <FlightNumber>7623</FlightNumber>
      |                    <DepartureAirport CodeContext="3">PFO</DepartureAirport>
      |                    <ArrivalAirport CodeContext="3">BHX</ArrivalAirport>
      |                    <OriginDate>2018-09-01</OriginDate>
      |                </LegIdentifier>
      |                <LegData InternationalStatus="International">
      |                    <PublicStatus xsi:nil="true"/>
      |                    <OperatingAlliance xsi:nil="true"/>
      |                    <ServiceType>C</ServiceType>
      |                    <EstFlightDuration xsi:nil="true"/>
      |                    <OwnerAirline xsi:nil="true"/>
      |                    <CabinClass Class="7">
      |                        <SeatCapacity>189</SeatCapacity>
      |                    </CabinClass>
      |                    <RemarkFreeText>ARR</RemarkFreeText>
      |                    <AirportResources Usage="Planned">
      |                        <Resource DepartureOrArrival="Arrival">
      |                            <AirportZone xsi:nil="true"/>
      |                            <AircraftParkingPosition>54L</AircraftParkingPosition>
      |                            <PassengerGate>44</PassengerGate>
      |                            <Runway xsi:nil="true"/>
      |                            <AircraftTerminal>1</AircraftTerminal>
      |                            <BaggageClaimUnit>3</BaggageClaimUnit>
      |                            <DeIceLocation xsi:nil="true"/>
      |                        </Resource>
      |                    </AirportResources>
      |                    <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="SCT">2018-09-01T23:00:00.000Z</OperationTime>
      |                    <OperationTime OperationQualifier="TDN" CodeContext="2005" TimeType="ACT">2018-09-01T23:00:00.000Z</OperationTime>
      |                    <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="ACT">2018-09-01T23:05:00.000Z</OperationTime>
      |                    <AircraftInfo>
      |                        <AircraftType>73H</AircraftType>
      |                        <AircraftSubType xsi:nil="true"/>
      |                        <Registration xsi:nil="true"/>
      |                        <TailNumber xsi:nil="true"/>
      |                        <AgentInfo DepartureOrArrival="Arrival">S</AgentInfo>
      |                        <FleetNumber xsi:nil="true"/>
      |                        <CallSign xsi:nil="true"/>
      |                    </AircraftInfo>
      |                </LegData>
      |                <TPA_Extension>
      |                    <TPA_KeyValue Key="AirlineName">Thomson Airways</TPA_KeyValue>
      |                    <TPA_KeyValue Key="DepartureAirportName">Paphos</TPA_KeyValue>
      |                    <TPA_KeyValue Key="ArrivalAirportName">Birmingham</TPA_KeyValue>
      |                </TPA_Extension>
      |            </FlightLeg>
      |            <FlightLeg>
      |                <LegIdentifier>
      |                    <Airline CodeContext="3">FR</Airline>
      |                    <FlightNumber>8045</FlightNumber>
      |                    <DepartureAirport CodeContext="3">CHQ</DepartureAirport>
      |                    <ArrivalAirport CodeContext="3">BHX</ArrivalAirport>
      |                    <OriginDate>2018-09-18</OriginDate>
      |                </LegIdentifier>
      |                <LegData InternationalStatus="International">
      |                    <PublicStatus xsi:nil="true"/>
      |                    <OperatingAlliance xsi:nil="true"/>
      |                    <ServiceType>J</ServiceType>
      |                    <EstFlightDuration xsi:nil="true"/>
      |                    <OwnerAirline xsi:nil="true"/>
      |                    <CabinClass Class="7">
      |                        <SeatCapacity>180</SeatCapacity>
      |                    </CabinClass>
      |                    <CabinClass Class="5">
      |                        <SeatCapacity>9</SeatCapacity>
      |                    </CabinClass>
      |                    <RemarkFreeText>ARR</RemarkFreeText>
      |                    <AirportResources Usage="Planned">
      |                        <Resource DepartureOrArrival="Arrival">
      |                            <AirportZone xsi:nil="true"/>
      |                            <AircraftParkingPosition>1</AircraftParkingPosition>
      |                            <PassengerGate>1</PassengerGate>
      |                            <Runway xsi:nil="true"/>
      |                            <AircraftTerminal>2</AircraftTerminal>
      |                            <BaggageClaimUnit>7</BaggageClaimUnit>
      |                            <DeIceLocation xsi:nil="true"/>
      |                        </Resource>
      |                    </AirportResources>
      |                    <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="SCT">2018-09-18T23:00:00.000Z</OperationTime>
      |                    <OperationTime OperationQualifier="TDN" CodeContext="2005" TimeType="ACT">2018-09-18T23:00:00.000Z</OperationTime>
      |                    <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="ACT">2018-09-18T23:05:00.000Z</OperationTime>
      |                    <AircraftInfo>
      |                        <AircraftType>73H</AircraftType>
      |                        <AircraftSubType xsi:nil="true"/>
      |                        <Registration xsi:nil="true"/>
      |                        <TailNumber xsi:nil="true"/>
      |                        <AgentInfo DepartureOrArrival="Arrival">S</AgentInfo>
      |                        <FleetNumber xsi:nil="true"/>
      |                        <CallSign xsi:nil="true"/>
      |                    </AircraftInfo>
      |                </LegData>
      |                <TPA_Extension>
      |                    <TPA_KeyValue Key="AirlineName">Ryanair</TPA_KeyValue>
      |                    <TPA_KeyValue Key="DepartureAirportName">Chania (s)</TPA_KeyValue>
      |                    <TPA_KeyValue Key="ArrivalAirportName">Birmingham</TPA_KeyValue>
      |                </TPA_Extension>
      |            </FlightLeg>
      |        </IATA_AIDX_FlightLegRS>
      |    </s:Body>
      |</s:Envelope>
    """.stripMargin
}
