package drt.server.feeds.lcy

import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import uk.gov.homeoffice.drt.time.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, Operator, Passengers, Predictions}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{AclFeedSource, LiveFeedSource, PortCode}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.xml.{NodeSeq, XML}

class LCYFlightTransformSpec extends CrunchTestLike {
  sequential
  isolated

  implicit val xmlToResUM: Unmarshaller[NodeSeq, LCYFlightsResponse] = LCYFlightTransform.unmarshaller
  implicit val resToBHXResUM: Unmarshaller[HttpResponse, LCYFlightsResponse] = LCYFlightTransform.responseToAUnmarshaller

  "Given some flight xml with one flight, I should get get back a list of 1 arrival" >> {

    val resp = HttpResponse(
      entity = HttpEntity(
        contentType = ContentType(MediaTypes.`application/xml`, HttpCharsets.`UTF-8`),
        lcySoapResponseOneFlightXml
      )
    )

    val result = Await.result(Unmarshal[HttpResponse](resp).to[LCYFlightsResponse], 5.seconds)
      .asInstanceOf[LCYFlightsResponseSuccess]
      .flights
    val expected = List(
      LCYFlight(
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
    )
    result === expected
  }

  "Given some flight xml with two flight, I should get get back a list of 2 arrival" >> {

    val resp = HttpResponse(
      entity = HttpEntity(
        contentType = ContentType(MediaTypes.`application/xml`, HttpCharsets.`UTF-8`),
        lcySoapResponseTwoFlightXml
      )
    )

    val result = Await.result(Unmarshal[HttpResponse](resp).to[LCYFlightsResponse], 5.seconds)
      .asInstanceOf[LCYFlightsResponseSuccess]
      .flights

    val expected = List(
      LCYFlight(
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
      ),
      LCYFlight(
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
    val result = LCYFlightTransform.scheduledTime(node).get

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
    val result = LCYFlightTransform.actualChox(node).get

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
    val result = LCYFlightTransform.estChox(node).get

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
    val result = LCYFlightTransform.estTouchDown(node).get

    result === expected
  }


  "Given a LCYFlight, I should get an Arrival back with the same fields - we should not use Est Chox" >> {
    val estimatedOnBlocksTimeString = "2018-09-01T23:05:00.000Z"
    val actualOnBlocksTimeString = "2018-09-01T23:06:00.000Z"
    val estimatedTouchDownTimeString = "2018-09-01T23:07:00.000Z"
    val actualTouchDownTimeString = "2018-09-01T23:08:00.000Z"
    val scheduledTimeString = "2018-09-01T23:00:00.000Z"

    val lcyFlight = LCYFlight(
      "SA",
      "123",
      "JNB",
      "LCY",
      "MT",
      "ARR",
      scheduledTimeString,
      arrival = true,
      international = true,
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

    val result = LCYFlightTransform.lcyFlightToArrival(lcyFlight)

    val expected = Arrival(
      Operator = Option(Operator("SA")),
      Status = ArrivalStatus("ARRIVED ON STAND"),
      Estimated = Option(SDate(estimatedTouchDownTimeString).millisSinceEpoch),
      Predictions = Predictions(0L, Map()),
      Actual = Option(SDate(actualTouchDownTimeString).millisSinceEpoch),
      EstimatedChox = None,
      ActualChox = Option(SDate(actualOnBlocksTimeString).millisSinceEpoch),
      Gate = Option("6"),
      Stand = Option("55"),
      MaxPax = Option(175),
      RunwayID = None,
      BaggageReclaimId = None,
      AirportID = PortCode("LCY"),
      Terminal = T1,
      rawICAO = "SA123",
      rawIATA = "SA123",
      Origin = PortCode("JNB"),
      Scheduled = SDate(scheduledTimeString).millisSinceEpoch,
      PcpTime = None,
      FeedSources = Set(LiveFeedSource),
      TotalPax = Map(LiveFeedSource -> Passengers(Option(65), None))
    )

    result === expected
  }

  def lcySoapResponseOneFlightXml: String =
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
      |      </IATA_AIDX_FlightLegRS>
      |   </s:Body>
      |</s:Envelope>
    """.stripMargin

  def lcySoapResponseTwoFlightXml: String =
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
}
