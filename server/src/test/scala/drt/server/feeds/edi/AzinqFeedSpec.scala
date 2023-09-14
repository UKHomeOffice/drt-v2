package drt.server.feeds.edi

import org.specs2.mutable.Specification
import spray.json.DefaultJsonProtocol._
import spray.json._
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, CarrierCode, Predictions, VoyageNumber, Passengers}
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.ports.Terminals.A2

class AzinqFeedSpec extends Specification {

  import AzinqArrivalEdiJsonFormats._

  "Given some json containing an edi flight" >> {
    "I should be able to parse it to an Arrival" >> {
      val arrival = json.parseJson.convertTo[List[AzinqEdiArrival]].map(_.toArrival)

      arrival === List(
        Arrival(
          Operator = None,
          CarrierCode = CarrierCode("ZT"),
          VoyageNumber = VoyageNumber(6566),
          FlightCodeSuffix = None,
          Status = ArrivalStatus("A"),
          Estimated = None,
          Predictions = Predictions(0, Map()),
          Actual = Some(1694669040000L),
          ActualChox = Some(1694671560000L),
          EstimatedChox = None,
          Gate = Some(""),
          Stand = None,
          MaxPax = Some(0),
          RunwayID = None,
          BaggageReclaimId = Some(""),
          AirportID = PortCode("EMA"),
          Terminal = A2,
          Origin = PortCode("EMA"),
          Scheduled = 1694669400000L,
          PcpTime = None,
          FeedSources = Set(LiveFeedSource),
          CarrierScheduled = None,
          ScheduledDeparture = None,
          RedListPax = None,
          PassengerSources = Map(LiveFeedSource -> Passengers(None, None)),
        )
      )
    }
  }

  def json: String =
    """[
      |  {
      |    "AIBT": "2023-09-14T07:06:00+01:00",
      |    "AircraftTypeDesc": "BOEING 767-200 FREIGHTER",
      |    "AircraftTypeIATA": "76X",
      |    "AircraftTypeICAO": "B762",
      |    "AirlineIATA": "ZT",
      |    "AirlineICAO": "AWC",
      |    "AirlineTicketed": "AWC",
      |    "ALDT": "2023-09-14T06:24:00+01:00",
      |    "AOBT": null,
      |    "AODBLinkedFlightId": 3071080,
      |    "ATOT": null,
      |    "BoardingCompleteDateTime": null,
      |    "BoardingStartDateTime": null,
      |    "CarouselCode": "",
      |    "CheckInFrom": "",
      |    "CheckInTo": "",
      |    "CodeShareFlights": "",
      |    "CodeShareInd": "N",
      |    "CodeSharePrimaryFlightId": null,
      |    "DepartureArrivalType": "A",
      |    "EstimatedDateTime": null,
      |    "FirstBagDateTime": null,
      |    "FlightIsCancelled": 0,
      |    "FlightNumber": "6566",
      |    "FlightStatus": "A",
      |    "GateActionCode": "",
      |    "GateChangeIndicator": null,
      |    "GateCode": "",
      |    "InternationalStatus": "D",
      |    "MaxPax": 0,
      |    "OriginDestAirportIATA": "EMA",
      |    "PublishedFlightId": 2967689,
      |    "Registration": "OYSRH",
      |    "Runway": "24",
      |    "ScheduledDateTime": "2023-09-14T06:30:00+01:00",
      |    "ServiceType": "F",
      |    "TerminalCode": "FRT",
      |    "TotalPassengerCount": null,
      |    "ZoneDateTime": "2023-09-14T06:14:00+01:00"
      |  }
      |]
      |""".stripMargin
}
