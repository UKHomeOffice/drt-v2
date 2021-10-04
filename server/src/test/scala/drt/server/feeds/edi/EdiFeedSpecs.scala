package drt.server.feeds.edi

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import drt.server.feeds.common.HttpClient
import drt.shared._
import drt.shared.api.{Arrival, FlightCodeSuffix}
import org.specs2.mock.Mockito.mock
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class EdiFeedSpecs extends CrunchTestLike {

  val ediJsonData =
    """
      |[{
      |        "FlightID": 2494582,
      |        "TicketedOperator": "FR",
      |        "AirlineCode_ICAO": "RYR",
      |        "AirlineCode_IATA": "FR",
      |        "AircraftTypeCode_ICAO": "B738",
      |        "AircraftTypeCode_IATA": "73H",
      |        "AircraftTypeDescription": "BOEING 737-800 WINGLETS",
      |        "FlightNumber": "1234",
      |        "AircraftRegistration": "EIEBG",
      |        "MAXPAX_Aircraft": 189,
      |        "AirportCode_IATA": "PSA",
      |        "DomsINtlCode": "I",
      |        "AirportEU_NonEU": "EEC",
      |        "ScheduledDateTime_Zulu": "2021-08-31T23:00:00",
      |        "ArrDeptureCode": "A",
      |        "FlightTypeCode_ACCORD": "J",
      |        "FlightTypeDescription": "SCHEDULED PASSENGER",
      |        "Sector": "International",
      |        "FlightStatus": "A",
      |        "FlightCancelled": 0,
      |        "Passengers": null,
      |        "EstimatedDateTime_Zulu": "2021-08-31T23:00:00",
      |        "ActualDateTime_Zulu": "2021-08-31T22:53:00",
      |        "ZoningDateTime_Zulu": "2021-08-31T22:43:00",
      |        "ChocksDateTime_Zulu": "2021-08-31T22:58:00",
      |        "BoardingStartDateTime_Zulu": null,
      |        "BoardingEndDateTime_Zulu": null,
      |        "FirstBagDateTime_Zulu": "2021-08-31T23:06:58",
      |        "CheckDesk_From": null,
      |        "CheckDesk_To": null,
      |        "DepartureGate": "14",
      |        "StandCode": "15A",
      |        "StandDescription": "STAND 15A",
      |        "RemoteStand": 0,
      |        "GateName": "GATE 14",
      |        "GateAction": null,
      |        "GateChange": null,
      |        "BagageReclaim": "8",
      |        "TerminalCode": "T1",
      |        "RunWayCode": "06",
      |        "CodeShares": null,
      |        "TurnaroundFlightID": 2564029
      |    }]
      |""".stripMargin

  "When HttpResponse with json entity from edi is given it can be unmarshall to EdiFlightDetails object" in {
    val ediFeed = new EdiFeed(EdiClient("", "", mock[HttpClient]))
    val httpResponse = HttpResponse().withEntity(HttpEntity(ContentTypes.`application/json`, ediJsonData))
    val data: Future[List[EdiFlightDetails]] = ediFeed.unMarshalResponseToEdiFlightDetails(httpResponse)

    val expectedResult = List(
      EdiFlightDetails("FR", "RYR", "FR", "1234", Option(189), "PSA", "I", "EEC", "2021-08-31T23:00:00",
        "A", Option("A"), None, Option("2021-08-31T23:00:00"), Option("2021-08-31T22:53:00"), Option("2021-08-31T22:43:00"),
        Option("2021-08-31T22:58:00"), Option("14"), Option("15A"), Option("GATE 14"), Option("8"), "T1", Option("06"))
    )

    val result = Await.result(data, 1 seconds)
    result mustEqual expectedResult

  }

  "Given EdiFlightDetails object , it gets transform to Arrival" in {
    val ediFeed = new EdiFeed(EdiClient("", "", mock[HttpClient]))

    val ediFlightDetail: EdiFlightDetails = EdiFlightDetails("FR", "RYR", "FR", "1234", Option(189),
      "PSA", "I", "EEC", "2021-08-31T23:00:00", "A", Option("A"), None, Option("2021-08-31T23:00:00"),
      Option("2021-08-31T22:53:00"), Option("2021-08-31T22:43:00"), Option("2021-08-31T22:58:00"),
      Option("14"), Option("15A"), Option("GATE 14"), Option("8"), "T1", Option("06"))

    val expectedArrival: List[Arrival] = List(
      Arrival(Some(Operator("FR")), CarrierCode("FR"), VoyageNumber(1234), None, ArrivalStatus("Arrival is on block at a stand"),
        Some(1630450800000L), Some(1630450380000L), None, Some(1630450680000L),
        Some("14"), Some("15A"), Some(189), None, None, Some("06"), Some("8"), PortCode("PSA"), Terminal("T1"), PortCode("PSA"),
        1630450800000L, None, Set(LiveFeedSource), None, None, None, None))

    val arrival = ediFeed.ediFlightDetailsToArrival(List(ediFlightDetail), LiveFeedSource)
    arrival mustEqual expectedArrival

  }

  "Regex to strip char from flightNumber if exists" in {
    val ediFeed = new EdiFeed(EdiClient("", "", mock[HttpClient]))
    val (voyageNumber1, flightCodeSuffix1) = ediFeed.flightNumberSplitToComponent("1234F")
    val (voyageNumber2, flightCodeSuffix2) = ediFeed.flightNumberSplitToComponent("1234")
    voyageNumber1 mustEqual VoyageNumber("1234")
    voyageNumber2 mustEqual VoyageNumber("1234")
    flightCodeSuffix1 must beSome(FlightCodeSuffix("F"))
    flightCodeSuffix2 must beNone
  }

}
