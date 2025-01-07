package feeds.cwl

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import drt.server.feeds.cwl._
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess, Feed}
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.xml.{NodeSeq, XML}

class CWLFeedSpec extends CrunchTestLike {
  sequential
  isolated

  implicit val xmlToResUM: Unmarshaller[NodeSeq, CWLFlightsResponse] = CWLFlight.unmarshaller
  implicit val resToCWLResUM: Unmarshaller[HttpResponse, CWLFlightsResponse] = CWLFlight.responseToAUnmarshaller

  "The CWL Feed client should successfully get a response from the CWL server" >> {
    skipped("Exploratory test - requires VPN connection, correct feed url and username env vars")

    val endpoint = sys.env("CWL_IATA_ENDPOINT_URL")
    val username = sys.env("CWL_IATA_USERNAME")

    val cwlClient = CWLClient(username, endpoint)
    Await.result(cwlClient.initialFlights, 30.seconds)

    false
  }

  "Given some flight xml with 1 flight with multiple passenger types, I should get 1 arrival with passengers summed" >> {

    val resp = HttpResponse(
      entity = HttpEntity(
        contentType = ContentType(MediaTypes.`application/xml`, HttpCharsets.`UTF-8`),
        multiplePassengerTypesXML
      )
    )

    val result = Await.result(Unmarshal[HttpResponse](resp).to[CWLFlightsResponse], 5.seconds)
      .asInstanceOf[CWLFlightsResponseSuccess]
      .flights
    val expected = List(
      CWLFlight(
        airline = "AA",
        flightNumber = "1234",
        departureAirport = "CDG",
        arrivalAirport = "CWL",
        aircraftTerminal = "T1",
        status = "LBG",
        scheduledOnBlocks = "2024-12-23T09:45:00.000Z",
        arrival = true,
        international = true,
        estimatedOnBlocks = Some("2024-12-23T09:55:00.000Z"),
        actualOnBlocks = Option("2024-12-23T09:59:00.000Z"),
        estimatedTouchDown = None,
        actualTouchDown = Option("2024-12-23T09:52:54.000Z"),
        aircraftParkingPosition = Option("10"),
        passengerGate = Option("10"),
        seatCapacity = Option(88),
        paxCount = Option(55)
      )
    )

    result === expected
  }

  "Given some flight xml with 2 flights, I should get get back 2 arrival objects" >> {

    val resp = HttpResponse(
      entity = HttpEntity(
        contentType = ContentType(MediaTypes.`application/xml`, HttpCharsets.`UTF-8`),
        cwlSoapResponse2FlightsXml
      )
    )

    val result = Await.result(Unmarshal[HttpResponse](resp).to[CWLFlightsResponse], 5.seconds)
      .asInstanceOf[CWLFlightsResponseSuccess]
      .flights.size

    result === 2
  }

  "Given a flight with multiple types of passengers, those passenger numbers should be added together" >> {
    val resp = HttpResponse(
      entity = HttpEntity(
        contentType = ContentType(MediaTypes.`application/xml`, HttpCharsets.`UTF-8`),
        multiplePassengerTypesXML
      )
    )

    val result = Await.result(Unmarshal[HttpResponse](resp).to[CWLFlightsResponse], 5.seconds)
      .asInstanceOf[CWLFlightsResponseSuccess]
      .flights
      .head
      .paxCount

    result === Option(55)
  }

  "Given only a departure flight no flights should be returned" >> {
    val resp = HttpResponse(
      entity = HttpEntity(
        contentType = ContentType(MediaTypes.`application/xml`, HttpCharsets.`UTF-8`),
        departureFlightXML
      )
    )

    val result = Await.result(Unmarshal[HttpResponse](resp).to[CWLFlightsResponse], 5.seconds)
      .asInstanceOf[CWLFlightsResponseSuccess]
      .flights.size

    result === 0
  }

  case class CWLMockClient(xmlResponse: String, cwlLiveFeedUser: String = "", soapEndPoint: String = "") extends CWLClientLike {


    def makeRequest(endpoint: String, headers: List[HttpHeader], postXML: String)
                   (implicit system: ActorSystem): Future[HttpResponse] = Future(HttpResponse(
      entity = HttpEntity(
        contentType = ContentType(MediaTypes.`application/xml`, HttpCharsets.`UTF-8`),
        xmlResponse
      )))
  }

  "Given a request for a full refresh of all flights, if it's successful the client should return all the flights" >> {
    val client = CWLMockClient(cwlSoapResponse2FlightsXml)

    val result = Await
      .result(client.initialFlights, 1.second).asInstanceOf[ArrivalsFeedSuccess].arrivals

    result === List(
      LiveArrival(Some("TOM"), Some(189), None, None, T1, 7623, "TOM", None, "PFO", 1535842800000L, None, Some(1535842800000L), None, Some(1535843100000L), "ARR", Some("44"), Some("54L"), None, None),
      LiveArrival(Some("FR"), Some(189), None, None, T2, 8045, "FR", None, "CHQ", 1537311600000L, None, Some(1537311600000L), None, Some(1537311900000L), "ARR", Some("1"), Some("1"), None, None)
    )
  }


  "Given a request for a full refresh of all flights, if we are rate limited then we should get an ArrivalsFeedFailure" >> {
    val client = CWLMockClient(rateLimitReachedResponse)

    val result = Await.result(client.initialFlights, 1.second)

    result must haveClass[ArrivalsFeedFailure]
  }

  "Given a mock client returning an invalid XML response I should get an ArrivalFeedFailure " >> {
    val client = CWLMockClient(invalidXmlResponse)

    val result = Await.result(client.initialFlights, 1.second)

    result must haveClass[ArrivalsFeedFailure]
  }

  case class CWLMockClientWithUpdates(initialResponses: List[ArrivalsFeedResponse], updateResponses: List[ArrivalsFeedResponse]) extends CWLClientLike {

    var mockInitialResponse: immutable.Seq[ArrivalsFeedResponse] = initialResponses
    var mockUpdateResponses: immutable.Seq[ArrivalsFeedResponse] = updateResponses

    override def initialFlights(implicit actorSystem: ActorSystem, materializer: Materializer): Future[ArrivalsFeedResponse] = mockInitialResponse match {
      case head :: tail =>
        mockInitialResponse = tail
        Future(head)
      case Nil =>
        Future(ArrivalsFeedFailure("No more mock esponses"))
    }

    override def updateFlights(implicit actorSystem: ActorSystem, materializer: Materializer): Future[ArrivalsFeedResponse] =
      mockUpdateResponses match {
        case head :: tail =>
          mockUpdateResponses = tail
          Future(head)

        case Nil =>
          Future(ArrivalsFeedFailure("No more mock esponses"))
      }

    def makeRequest(endpoint: String, headers: List[HttpHeader], postXML: String)
                   (implicit system: ActorSystem): Future[HttpResponse] = ???

    override val cwlLiveFeedUser: String = ""
    override val soapEndPoint: String = ""
  }

  "Given a request for a full refresh of all flights fails, we should poll for a full request until it succeeds" >> {
    val firstFailure = ArrivalsFeedFailure("First Failure")
    val secondFailure = ArrivalsFeedFailure("Second Failure")
    val finallySuccess = ArrivalsFeedSuccess(List())

    val initialResponses = List(firstFailure, secondFailure, finallySuccess)
    val updateResponses = List(finallySuccess)

    val feed = CWLFeed(
      CWLMockClientWithUpdates(initialResponses, updateResponses),
      Feed.actorRefSource
    )

    val probe = TestProbe()
    val expected = Seq(firstFailure, secondFailure, finallySuccess, finallySuccess)
    val actorSource = feed.take(4).to(Sink.actorRef(probe.ref, NotUsed)).run()
    Source(1 to 4).map(_ => actorSource ! Feed.Tick).run()

    probe.receiveN(4, 1.second) === expected
  }

  "Given a successful initial request, followed by a failed update, we should continue to poll for updates" >> {

    val failure = ArrivalsFeedFailure("First Failure")
    val finallySuccess = ArrivalsFeedSuccess(List())

    val initialResponses = List(finallySuccess)
    val updateResponses = List(failure, finallySuccess)

    val feed = CWLFeed(
      CWLMockClientWithUpdates(initialResponses, updateResponses),
      Feed.actorRefSource
    )

    val expected = Seq(finallySuccess, failure, finallySuccess)
    val probe = TestProbe()
    val actorSource = feed.take(3).to(Sink.actorRef(probe.ref, NotUsed)).run()
    Source(1 to 3).map(_ => actorSource ! Feed.Tick).run()

    probe.receiveN(3, 1.second) === expected
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
    val result = CWLFlight.scheduledTime(node).get

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
    val result = CWLFlight.actualChox(node).get

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
    val result = CWLFlight.estChox(node).get

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
    val result = CWLFlight.estTouchDown(node).get

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
    val result = CWLFlight.actualTouchDown(node).get

    result === expected
  }

  def multiplePassengerTypesXML: String =
    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
      |    <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      |        <IATA_AIDX_FlightLegRS TimeStamp="2019-07-25T09:13:19.4014748+01:00" Version="16.1" xmlns="http://www.iata.org/IATA/2007/00">
      |          <Success/>
      |          <FlightLeg>
      |            <LegIdentifier>
      |              <Airline CodeContext="3">AA</Airline>
      |              <FlightNumber>1234</FlightNumber>
      |              <DepartureAirport CodeContext="3">CDG</DepartureAirport>
      |              <ArrivalAirport CodeContext="3">CWL</ArrivalAirport>
      |              <OriginDate>2024-12-23</OriginDate>
      |            </LegIdentifier>
      |            <LegData InternationalStatus="International">
      |              <PublicStatus xsi:nil="true"/>
      |              <OperatingAlliance xsi:nil="true"/>
      |              <ServiceType>J</ServiceType>
      |              <EstFlightDuration xsi:nil="true"/>
      |              <OwnerAirline xsi:nil="true"/>
      |              <CabinClass Class="7">
      |                <PaxCount Qualifier="A" Usage="Planned" DestinationType="Local">50</PaxCount>
      |                <PaxCount Qualifier="IN" Usage="Planned" DestinationType="Local">5</PaxCount>
      |                <SeatCapacity>88</SeatCapacity>
      |              </CabinClass>
      |              <CodeShareInfo>
      |                <Airline>DL</Airline>
      |                <FlightNumber>1111</FlightNumber>
      |              </CodeShareInfo>
      |              <CodeShareInfo>
      |                <Airline>EY</Airline>
      |                <FlightNumber>2222</FlightNumber>
      |              </CodeShareInfo>
      |              <CodeShareInfo>
      |                <Airline>KQ</Airline>
      |                <FlightNumber>3333</FlightNumber>
      |              </CodeShareInfo>
      |              <CodeShareInfo>
      |                <Airline>VS</Airline>
      |                <FlightNumber>4444</FlightNumber>
      |              </CodeShareInfo>
      |              <RemarkFreeText>LBG</RemarkFreeText>
      |              <AirportResources Usage="Planned">
      |                <Resource DepartureOrArrival="Arrival">
      |                  <AirportZone xsi:nil="true"/>
      |                  <AircraftParkingPosition>10</AircraftParkingPosition>
      |                  <PassengerGate>10</PassengerGate>
      |                  <Runway>30</Runway>
      |                  <AircraftTerminal>T1</AircraftTerminal>
      |                  <BaggageClaimUnit>A</BaggageClaimUnit>
      |                </Resource>
      |              </AirportResources>
      |              <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="SCT">2024-12-23T09:45:00.000Z</OperationTime>
      |              <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="EST">2024-12-23T09:55:00.000Z</OperationTime>
      |              <OperationTime OperationQualifier="TDN" CodeContext="2005" TimeType="ACT">2024-12-23T09:52:54.000Z</OperationTime>
      |              <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="ACT">2024-12-23T09:59:00.000Z</OperationTime>
      |              <AircraftInfo>
      |                <AircraftType>E90</AircraftType>
      |                <AircraftSubType xsi:nil="true"/>
      |                <Registration>PHEZY</Registration>
      |                <TailNumber xsi:nil="true"/>
      |                <AgentInfo DepartureOrArrival="Arrival">SER</AgentInfo>
      |                <FleetNumber xsi:nil="true"/>
      |                <CallSign>KLM69Y</CallSign>
      |              </AircraftInfo>
      |            </LegData>
      |            <TPA_Extension/>
      |          </FlightLeg>
      |        </IATA_AIDX_FlightLegRS>
      |    </s:Body>
      |</s:Envelope>
    """.stripMargin

  def departureFlightXML: String =
    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
      |    <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      |        <IATA_AIDX_FlightLegRS TimeStamp="2019-07-25T09:13:19.4014748+01:00" Version="16.1" xmlns="http://www.iata.org/IATA/2007/00">
      |          <Success/>
      |          <FlightLeg>
      |            <LegIdentifier>
      |              <Airline CodeContext="3">RUK</Airline>
      |              <FlightNumber>9431</FlightNumber>
      |              <DepartureAirport CodeContext="3">CWL</DepartureAirport>
      |              <ArrivalAirport CodeContext="3">BFS</ArrivalAirport>
      |              <OriginDate>2023-07-02</OriginDate>
      |            </LegIdentifier>
      |            <LegData InternationalStatus="Domestic">
      |              <PublicStatus xsi:nil="true"/>
      |              <OperatingAlliance xsi:nil="true"/>
      |              <ServiceType>J</ServiceType>
      |              <EstFlightDuration xsi:nil="true"/>
      |              <OwnerAirline xsi:nil="true"/>
      |              <CabinClass Class="7">
      |                <SeatCapacity xsi:nil="true"/>
      |              </CabinClass>
      |              <RemarkFreeText>DEP</RemarkFreeText>
      |              <AirportResources Usage="Planned">
      |                <Resource DepartureOrArrival="Departure">
      |                  <AirportZone xsi:nil="true"/>
      |                  <AircraftParkingPosition>11</AircraftParkingPosition>
      |                  <Runway>30</Runway>
      |                  <AircraftTerminal xsi:nil="true"/>
      |                </Resource>
      |              </AirportResources>
      |              <OperationTime OperationQualifier="OFB" CodeContext="2005" TimeType="SCT">2023-07-02T13:02:00.000Z</OperationTime>
      |              <OperationTime OperationQualifier="TKO" CodeContext="2005" TimeType="ACT">2023-07-02T13:02:00.000Z</OperationTime>
      |              <OperationTime OperationQualifier="OFB" CodeContext="2005" TimeType="ACT">2023-07-02T12:57:00.000Z</OperationTime>
      |              <AircraftInfo>
      |                <AircraftType>73H</AircraftType>
      |                <AircraftSubType xsi:nil="true"/>
      |                <Registration>GRUKK</Registration>
      |                <TailNumber xsi:nil="true"/>
      |                <AgentInfo DepartureOrArrival="Departure">NON</AgentInfo>
      |                <FleetNumber xsi:nil="true"/>
      |                <CallSign>RUK9431</CallSign>
      |              </AircraftInfo>
      |            </LegData>
      |            <TPA_Extension/>
      |          </FlightLeg>
      |        </IATA_AIDX_FlightLegRS>
      |    </s:Body>
      |</s:Envelope>
    """.stripMargin

  def cwlSoapResponse2FlightsXml: String =
    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
      |    <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      |        <IATA_AIDX_FlightLegRS TimeStamp="2019-07-25T09:13:19.4014748+01:00" Version="16.1" xmlns="http://www.iata.org/IATA/2007/00">
      |            <Success/>
      |            <FlightLeg>
      |                <LegIdentifier>
      |                    <Airline CodeContext="3">TOM</Airline>
      |                    <FlightNumber>7623</FlightNumber>
      |                    <DepartureAirport CodeContext="3">PFO</DepartureAirport>
      |                    <ArrivalAirport CodeContext="3">CWL</ArrivalAirport>
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
      |                            <AircraftTerminal>T1</AircraftTerminal>
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
      |                    <ArrivalAirport CodeContext="3">CWL</ArrivalAirport>
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
      |                            <AircraftTerminal>T2</AircraftTerminal>
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

  def rateLimitReachedResponse: String =
    """
      |<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
      |    <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      |        <IATA_AIDX_FlightLegRS Version="16.1" xmlns="http://www.iata.org/IATA/2007/00">
      |            <Success/>
      |            <Warnings>
      |                <Warning Type="911">Warning: Full Refresh not possible at this time please try in 590 seconds.</Warning>
      |            </Warnings>
      |        </IATA_AIDX_FlightLegRS>
      |    </s:Body>
      |</s:Envelope>
      """.stripMargin

  def invalidXmlResponse: String =
    """
      |Blah blah
      """.stripMargin
}
