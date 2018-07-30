package drt.chroma

import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import drt.chroma.chromafetcher.ChromaFetcher.ChromaLiveFlight
import drt.chroma.chromafetcher.ChromaParserProtocol
import drt.chroma.chromafetcher.ChromaParserProtocol._
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import server.feeds.{ArrivalsFeedSuccess, FeedResponse}
import services.SDate
import spray.json._

import scala.collection.immutable.Seq

class DiffingAkkaStreamSpec extends AkkaStreamTestKitSpecificationLike with SampleData {

  "Given a our initial response we emit all entries" >> {
    val source = Source(Seq(response0))

    "we can parse it" in {
      source.map(content => content.parseJson.convertTo[List[ChromaLiveFlight]])
        .runWith(TestSink.probe[List[ChromaLiveFlight]])
        .request(1)
        .expectNext(List(
          ChromaLiveFlight("Tnt Airways Sa", "On Chocks",
            "2016-08-04T04:40:00Z",
            "2016-08-04T04:37:00Z",
            "",
            "2016-08-04T04:53:00Z", "", "207", 0, 0, 0, "24", "",
            1200980, "EDI", "FRT", "TAY025N", "3V025N", "LGG", "2016-08-04T04:35:00Z"
          )))
        .expectComplete()
    }
  }

  "We can poll twice and get two full responses" >> {
    val source = Source(Seq(response0, response1))

    "we can parse it" in {
      source.map(content => content.parseJson.convertTo[List[ChromaLiveFlight]])
        .runWith(TestSink.probe[List[ChromaLiveFlight]])
        .requestNext(List(
          ChromaLiveFlight("Tnt Airways Sa", "On Chocks",
            "2016-08-04T04:40:00Z",
            "2016-08-04T04:37:00Z",
            "",
            "2016-08-04T04:53:00Z", "", "207", 0, 0, 0, "24", "",
            1200980, "EDI", "FRT", "TAY025N", "3V025N", "LGG", "2016-08-04T04:35:00Z"
          )))
        .requestNext(List(
          ChromaLiveFlight("Tnt Airways Sa", "On Chocks",
            "2016-08-04T04:40:00Z",
            "2016-08-04T04:37:00Z",
            "",
            "2016-08-04T04:53:00Z", "", "207", 0, 0, 0, "24", "",
            1200980, "EDI", "FRT", "TAY025N", "3V025N", "LGG", "2016-08-04T04:35:00Z"
          ), flight2))
        .expectComplete()
    }
  }

  "Given two pollings where the second drt.http response has two entries we should emit both flights a single time" >> {
    val source = Source(Seq(response0, response1))

    "we really can diff it and parse it" in {
      val date = SDate.now()
      source
        .map(content => content.parseJson.convertTo[List[ChromaLiveFlight]])
        .map(chromaArrivals => ArrivalsFeedSuccess(Flights(StreamingChromaFlow.liveChromaToArrival(chromaArrivals)), date))
        .via(new ArrivalsDiffingStage(Seq()))
        .runWith(TestSink.probe[FeedResponse])
        .requestNext(ArrivalsFeedSuccess(Flights(StreamingChromaFlow.liveChromaToArrival(List(
          ChromaLiveFlight("Tnt Airways Sa", "On Chocks",
            "2016-08-04T04:40:00Z",
            "2016-08-04T04:37:00Z",
            "",
            "2016-08-04T04:53:00Z", "", "207", 0, 0, 0, "24", "",
            1200980, "EDI", "FRT", "TAY025N", "3V025N", "LGG", "2016-08-04T04:35:00Z"
          )))), date))
        .requestNext(ArrivalsFeedSuccess(Flights(StreamingChromaFlow.liveChromaToArrival(List(flight2))), date))
        .expectComplete()
    }
  }

  "Given two polls where a flight details changes on the second we emit the change details" >> {
    val date = SDate.now()
    val source = Source(Seq(
      List(flight1),
      List(flight1.copy(ActDT = "2016-08-04T09:11:00Z", ActChoxDT = "2016-08-04T04:54:00Z"))))

    "we really can diff it and parse it" in {
      source
        .map(chromaArrivals => ArrivalsFeedSuccess(Flights(StreamingChromaFlow.liveChromaToArrival(chromaArrivals)), date))
        .via(new ArrivalsDiffingStage(Seq()))
        .runWith(TestSink.probe[FeedResponse])
        .requestNext(ArrivalsFeedSuccess(Flights(StreamingChromaFlow.liveChromaToArrival(List(
          ChromaLiveFlight("Tnt Airways Sa", "On Chocks",
            "2016-08-04T04:40:00Z",
            "2016-08-04T04:37:00Z",
            "",
            "2016-08-04T04:53:00Z", "", "207", 0, 0, 0, "24", "",
            1200980, "EDI", "FRT", "TAY025N", "3V025N", "LGG", "2016-08-04T04:35:00Z"
          )))), date))
        .requestNext(ArrivalsFeedSuccess(Flights(StreamingChromaFlow.liveChromaToArrival(List(
          ChromaLiveFlight("Tnt Airways Sa", "On Chocks",
          "2016-08-04T04:40:00Z",
          "2016-08-04T09:11:00Z",
          "",
          "2016-08-04T04:54:00Z", "", "207", 0, 0, 0, "24", "",
          1200980, "EDI", "FRT", "TAY025N", "3V025N", "LGG", "2016-08-04T04:35:00Z"
        )))), date))
        .expectComplete()
    }
  }

  val response0 =
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
      |    "MaxPax": 0,
      |    "ActPax": 0,
      |    "TranPax": 0,
      |    "RunwayID": "24",
      |    "BaggageReclaimId": "",
      |    "FlightID": 1200980,
      |    "AirportID": "EDI",
      |    "Terminal": "FRT",
      |    "ICAO": "TAY025N",
      |    "IATA": "3V025N",
      |    "Origin": "LGG",
      |    "SchDT": "2016-08-04T04:35:00Z"
      |  }
      |  ]
    """.stripMargin

  val response1 =
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
      |    "MaxPax": 0,
      |    "ActPax": 0,
      |    "TranPax": 0,
      |    "RunwayID": "24",
      |    "BaggageReclaimId": "",
      |    "FlightID": 1200980,
      |    "AirportID": "EDI",
      |    "Terminal": "FRT",
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
      |    "MaxPax": 0,
      |    "ActPax": 0,
      |    "TranPax": 0,
      |    "RunwayID": "24",
      |    "BaggageReclaimId": "",
      |    "FlightID": 1200986,
      |    "AirportID": "EDI",
      |    "Terminal": "FRT",
      |    "ICAO": "SRR6566",
      |    "IATA": "S66566",
      |    "Origin": "CGN",
      |    "SchDT": "2016-08-04T05:15:00Z"
      |  }
      |  ]
    """.stripMargin
}
