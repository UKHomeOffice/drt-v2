package drt.chroma

import drt.chroma.chromafetcher.ChromaFetcherLive.ChromaSingleFlight
import spray.http.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}

trait SampleData {

  val flight1: ChromaSingleFlight = ChromaSingleFlight("Tnt Airways Sa", "On Chocks",
    "2016-08-04T04:40:00Z",
    "2016-08-04T04:37:00Z",
    "",
    "2016-08-04T04:53:00Z", "", "207", 0, 0, 0, "24", "",
    1200980, "EDI", "FRT", "TAY025N", "3V025N", "LGG", "2016-08-04T04:35:00Z"
  )
  val flight2 = ChromaSingleFlight("Star Air", "On Chocks", "", "2016-08-04T05:32:00Z", "",
    "2016-08-04T05:41:00Z", "", "212",
    0, 0, 0, "24", "", 1200986, "EDI",
    "FRT", "SRR6566", "S66566", "CGN",
    "2016-08-04T05:15:00Z")
  val flightWithUnknownStatus: ChromaSingleFlight = ChromaSingleFlight("Tnt Airways Sa", "Non existent status",
    "2016-08-04T04:40:00Z",
    "2016-08-04T04:37:00Z",
    "",
    "2016-08-04T04:53:00Z", "", "207", 0, 0, 0, "24", "",
    1200980, "EDI", "T1", "TAY025N", "3V025N", "LGG", "2016-08-04T04:35:00Z"
  )

  val successfulChromaResponse1 = HttpResponse(StatusCodes.OK,
    HttpEntity(ContentTypes.`application/json`,
      """
        |[
        |  {
        |    "Operator": "Tnt Airways Sa",
        |    "Status": "On Chocks",
        |    "EstDT": "2016-08-04T04:40:00Z",
        |    "ActDT": "2016-08-04T04:37:00Z",
        |    "EstChoxDT": "",
        |    "ActChoxDT": "2016-08-04T04:53:00Z",
        |    "Gate": "",
        |    "Stand": "207",
        |    "MaxPax": 200,
        |    "ActPax": 120,
        |    "TranPax": 0,
        |    "RunwayID": "24",
        |    "BaggageReclaimId": "",
        |    "FlightID": 1200980,
        |    "AirportID": "EDI",
        |    "Terminal": "T1",
        |    "ICAO": "TAY025N",
        |    "IATA": "3V025N",
        |    "Origin": "LGG",
        |    "SchDT": "2016-08-04T04:35:00Z"
        |  },
        |  {
        |    "Operator": "Star Air",
        |    "Status": "On Chocks",
        |    "EstDT": "",
        |    "ActDT": "2016-08-04T05:32:00Z",
        |    "EstChoxDT": "",
        |    "ActChoxDT": "2016-08-04T05:41:00Z",
        |    "Gate": "",
        |    "Stand": "212",
        |    "MaxPax": 150,
        |    "ActPax": 10,
        |    "TranPax": 0,
        |    "RunwayID": "24",
        |    "BaggageReclaimId": "",
        |    "FlightID": 1200986,
        |    "AirportID": "EDI",
        |    "Terminal": "T1",
        |    "ICAO": "SRR6566",
        |    "IATA": "S66566",
        |    "Origin": "CGN",
        |    "SchDT": "2016-08-04T05:15:00Z"
        |  }
        |  ]
      """.stripMargin))

  val successfulChromaResponse2 =
    HttpResponse(StatusCodes.OK,
      HttpEntity(ContentTypes.`application/json`,
        """
          |[{
          |    "Operator": "Klm",
          |    "Status": "Scheduled",
          |    "EstDT": "2016-08-04T16:05:00Z",
          |    "ActDT": "",
          |    "EstChoxDT": "2016-08-04T16:15:00Z",
          |    "ActChoxDT": "",
          |    "Gate": "",
          |    "Stand": "1B",
          |    "MaxPax": 100,
          |    "ActPax": 50,
          |    "TranPax": 0,
          |    "RunwayID": "",
          |    "BaggageReclaimId": "3",
          |    "FlightID": 1201239,
          |    "AirportID": "EDI",
          |    "Terminal": "T1",
          |    "ICAO": "KLM1289",
          |    "IATA": "KL1289",
          |    "Origin": "AMS",
          |    "SchDT": "2016-08-04T16:00:00Z"
          |}]
        """.stripMargin))
}

object SampleData extends SampleData
