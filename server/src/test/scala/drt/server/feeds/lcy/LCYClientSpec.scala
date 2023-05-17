package drt.server.feeds.lcy

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import drt.server.feeds.common.ProdHttpClient
import drt.shared.FlightsApi.Flights
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.LiveFeedSource

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class LCYClientSpec extends CrunchTestLike with Mockito {

  val httpClient = mock[ProdHttpClient]

  trait Context extends Scope {
    val lcyClient = LCYClient(httpClient, "someUser", "someSoapEndPoint", "someUsername", "somePassword")
  }

  "Given a request for a full refresh of all flights, if it's successful the client should return all the flights" in new Context {

    httpClient.sendRequest(anyObject[HttpRequest]) returns Future(HttpResponse(entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, lcySoapResponseTwoFlightXml)))

    val flight1 = LCYFlight(
      "MMD",
      "5055",
      "SGD",
      "LCY",
      "MT",
      "LND",
      "2019-11-18T13:00:00.000Z",
      arrival = true,
      international = true,
      Option("2019-11-18T12:47:00.000Z"),
      Option("2019-11-18T12:49:00.000Z"),
      None,
      Option("2019-11-18T12:47:00.000Z"),
      Option("MT"),
      None,
      Option(14),
      None
    )

    val flight2 = LCYFlight(
      "AFP",
      "24",
      "TOJ",
      "LCY",
      "JC",
      "LND",
      "2019-12-03T14:50:00.000Z",
      arrival = true,
      international = true,
      None,
      Option("2019-12-03T12:12:00.000Z"),
      None,
      Option("2019-12-03T12:08:00.000Z"),
      Option("JC"),
      None,
      None,
      None
    )


    val result: Flights = Await.result(lcyClient.initialFlights, 1.second).asInstanceOf[ArrivalsFeedSuccess].arrivals
    val expected = Flights(List(
      LCYFlightTransform.lcyFlightToArrival(flight1),
      LCYFlightTransform.lcyFlightToArrival(flight2)
    ))

    result === expected
  }

  "Given a request for a full refresh of all flights, if we are rate limited then we should get an ArrivalsFeedFailure" in new Context {
    httpClient.sendRequest(anyObject[HttpRequest]) returns Future(HttpResponse(entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, rateLimitReachedResponse)))

    val result = Await.result(lcyClient.initialFlights, 1.second)

    result must haveClass[ArrivalsFeedFailure]
  }

  "Given a mock client returning an invalid XML response I should get an ArrivalFeedFailure " in new Context {

    httpClient.sendRequest(anyObject[HttpRequest]) returns Future(HttpResponse(entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, invalidXmlResponse)))


    val result = Await.result(lcyClient.initialFlights, 1.second)

    result must haveClass[ArrivalsFeedFailure]
  }


  "Given a LCYFlight with 0 for passenger fields, I should see 0 pax, 0 max pax and 0 transfer pax." in new Context {
    httpClient.sendRequest(anyObject[HttpRequest]) returns Future(HttpResponse(entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, lcySoapResponseZeroPaxFlightXml)))

    val result: Flights = Await.result(lcyClient.initialFlights, 1.second).asInstanceOf[ArrivalsFeedSuccess].arrivals

    val actMax = result match {
      case Flights(f :: _) => (f.PassengerSources.get(LiveFeedSource).flatMap(_.actual), f.MaxPax)
    }

    val expected = (None, Some(0))

    actMax === expected
  }


  val lcySoapResponseTwoFlightXml: String =
    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
      |   <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      |      <IATA_AIDX_FlightLegRS TimeStamp="2020-07-03T10:59:35.1977952+01:00" Version="13.2" xmlns="http://www.iata.org/IATA/2007/00">
      |         <Success/>
      |         <FlightLeg>
      |            <LegIdentifier>
      |               <Airline CodeContext="3">MMD</Airline>
      |               <FlightNumber>5055</FlightNumber>
      |               <DepartureAirport CodeContext="3">SGD</DepartureAirport>
      |               <ArrivalAirport CodeContext="3">LCY</ArrivalAirport>
      |               <OriginDate>2019-11-18</OriginDate>
      |            </LegIdentifier>
      |            <LegData InternationalStatus="International">
      |               <PublicStatus xsi:nil="true"/>
      |               <OperatingAlliance xsi:nil="true"/>
      |               <ServiceType>N</ServiceType>
      |               <EstFlightDuration xsi:nil="true"/>
      |               <OwnerAirline xsi:nil="true"/>
      |               <CabinClass Class="7">
      |                  <SeatCapacity>14</SeatCapacity>
      |               </CabinClass>
      |               <RemarkFreeText>LND</RemarkFreeText>
      |               <AirportResources Usage="Planned">
      |                  <Resource DepartureOrArrival="Arrival">
      |                     <AirportZone xsi:nil="true"/>
      |                     <AircraftParkingPosition>MT</AircraftParkingPosition>
      |                     <Runway>27</Runway>
      |                     <AircraftTerminal>MT</AircraftTerminal>
      |                     <BaggageClaimUnit>03</BaggageClaimUnit>
      |                  </Resource>
      |               </AirportResources>
      |               <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="SCT">2019-11-18T13:00:00.000Z</OperationTime>
      |               <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="EST">2019-11-18T12:47:00.000Z</OperationTime>
      |               <OperationTime OperationQualifier="TDN" CodeContext="2005" TimeType="ACT">2019-11-18T12:47:00.000Z</OperationTime>
      |               <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="ACT">2019-11-18T12:49:00.000Z</OperationTime>
      |               <AircraftInfo>
      |                  <AircraftType>DF3</AircraftType>
      |                  <AircraftSubType xsi:nil="true"/>
      |                  <Registration>OYRAB</Registration>
      |                  <TailNumber xsi:nil="true"/>
      |                  <AgentInfo DepartureOrArrival="Arrival">J</AgentInfo>
      |                  <FleetNumber xsi:nil="true"/>
      |                  <CallSign>MMD5055</CallSign>
      |               </AircraftInfo>
      |            </LegData>
      |            <TPA_Extension/>
      |         </FlightLeg>
      |         <FlightLeg>
      |            <LegIdentifier>
      |               <Airline CodeContext="3">AFP</Airline>
      |               <FlightNumber>24</FlightNumber>
      |               <DepartureAirport CodeContext="3">TOJ</DepartureAirport>
      |               <ArrivalAirport CodeContext="3">LCY</ArrivalAirport>
      |               <OriginDate>2019-12-03</OriginDate>
      |            </LegIdentifier>
      |            <LegData InternationalStatus="International">
      |               <PublicStatus xsi:nil="true"/>
      |               <OperatingAlliance xsi:nil="true"/>
      |               <ServiceType>D</ServiceType>
      |               <EstFlightDuration xsi:nil="true"/>
      |               <OwnerAirline xsi:nil="true"/>
      |               <CabinClass Class="7">
      |                  <SeatCapacity xsi:nil="true"/>
      |               </CabinClass>
      |               <RemarkFreeText>LND</RemarkFreeText>
      |               <AirportResources Usage="Planned">
      |                  <Resource DepartureOrArrival="Arrival">
      |                     <AirportZone xsi:nil="true"/>
      |                     <AircraftParkingPosition>JC</AircraftParkingPosition>
      |                     <Runway>09</Runway>
      |                     <AircraftTerminal>JC</AircraftTerminal>
      |                  </Resource>
      |               </AirportResources>
      |               <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="SCT">2019-12-03T14:50:00.000Z</OperationTime>
      |               <OperationTime OperationQualifier="TDN" CodeContext="2005" TimeType="ACT">2019-12-03T12:08:00.000Z</OperationTime>
      |               <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="ACT">2019-12-03T12:12:00.000Z</OperationTime>
      |               <AircraftInfo>
      |                  <AircraftType>FA50</AircraftType>
      |                  <AircraftSubType xsi:nil="true"/>
      |                  <Registration>17401</Registration>
      |                  <TailNumber xsi:nil="true"/>
      |                  <AgentInfo DepartureOrArrival="Arrival">J</AgentInfo>
      |                  <FleetNumber xsi:nil="true"/>
      |                  <CallSign>AFP24</CallSign>
      |               </AircraftInfo>
      |            </LegData>
      |            <TPA_Extension/>
      |         </FlightLeg>
      |      </IATA_AIDX_FlightLegRS>
      |   </s:Body>
      |</s:Envelope>
    """.stripMargin


  val lcySoapResponseZeroPaxFlightXml: String =
    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
      |   <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      |      <IATA_AIDX_FlightLegRS TimeStamp="2020-07-03T10:59:35.1977952+01:00" Version="13.2" xmlns="http://www.iata.org/IATA/2007/00">
      |         <Success/>
      |         <FlightLeg>
      |            <LegIdentifier>
      |               <Airline CodeContext="3">MMD</Airline>
      |               <FlightNumber>5055</FlightNumber>
      |               <DepartureAirport CodeContext="3">SGD</DepartureAirport>
      |               <ArrivalAirport CodeContext="3">LCY</ArrivalAirport>
      |               <OriginDate>2019-11-18</OriginDate>
      |            </LegIdentifier>
      |            <LegData InternationalStatus="International">
      |               <PublicStatus xsi:nil="true"/>
      |               <OperatingAlliance xsi:nil="true"/>
      |               <ServiceType>N</ServiceType>
      |               <EstFlightDuration xsi:nil="true"/>
      |               <OwnerAirline xsi:nil="true"/>
      |               <CabinClass Class="7">
      |                  <SeatCapacity>0</SeatCapacity>
      |               </CabinClass>
      |               <RemarkFreeText>LND</RemarkFreeText>
      |               <AirportResources Usage="Planned">
      |                  <Resource DepartureOrArrival="Arrival">
      |                     <AirportZone xsi:nil="true"/>
      |                     <AircraftParkingPosition>MT</AircraftParkingPosition>
      |                     <Runway>27</Runway>
      |                     <AircraftTerminal>MT</AircraftTerminal>
      |                     <BaggageClaimUnit>03</BaggageClaimUnit>
      |                  </Resource>
      |               </AirportResources>
      |               <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="SCT">2019-11-18T13:00:00.000Z</OperationTime>
      |               <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="EST">2019-11-18T12:47:00.000Z</OperationTime>
      |               <OperationTime OperationQualifier="TDN" CodeContext="2005" TimeType="ACT">2019-11-18T12:47:00.000Z</OperationTime>
      |               <OperationTime OperationQualifier="ONB" CodeContext="2005" TimeType="ACT">2019-11-18T12:49:00.000Z</OperationTime>
      |               <AircraftInfo>
      |                  <AircraftType>DF3</AircraftType>
      |                  <AircraftSubType xsi:nil="true"/>
      |                  <Registration>OYRAB</Registration>
      |                  <TailNumber xsi:nil="true"/>
      |                  <AgentInfo DepartureOrArrival="Arrival">J</AgentInfo>
      |                  <FleetNumber xsi:nil="true"/>
      |                  <CallSign>MMD5055</CallSign>
      |               </AircraftInfo>
      |            </LegData>
      |            <TPA_Extension/>
      |         </FlightLeg>
      |      </IATA_AIDX_FlightLegRS>
      |   </s:Body>
      |</s:Envelope>
    """.stripMargin


  val rateLimitReachedResponse: String =
    """
      |<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
      |    <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      |        <IATA_AIDX_FlightLegRS Version="16.1" xmlns="http://www.iata.org/IATA/2007/00">
      |            <Success/>
      |            <Warnings>
      |                <Warning Type="911">Warning: Full Refresh not possible at this time please try in 372.7183059 seconds.</Warning>
      |            </Warnings>
      |        </IATA_AIDX_FlightLegRS>
      |    </s:Body>
      |</s:Envelope>
    """.stripMargin

  val invalidXmlResponse: String =
    """
      |Blah blah
    """.stripMargin
}
