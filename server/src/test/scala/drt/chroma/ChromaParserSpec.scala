package drt.chroma

import drt.chroma.chromafetcher.ChromaFetcher.ChromaSingleFlight
import drt.chroma.chromafetcher.ChromaParserProtocol
import org.specs2.mutable.SpecificationLike


class ChromaParserSpec extends SpecificationLike {

  import ChromaParserProtocol._

  //  sequential
  "Parse chroma response" >> {
    "Given a chroma response list with a single Flight we can parse it" in {
      import spray.json._
      val chromaFlightJson: JsValue =
        """
          |[{
          |"Operator":"Flybe"
          	 |,"Status":"On Chocks"
          	 |,"EstDT":"2016-06-02T10:55:00Z"
          	 |,"ActDT":"2016-06-02T10:55:00Z"
          	 |,"EstChoxDT":"2016-06-02T11:01:00Z"
          	 |,"ActChoxDT":"2016-06-02T11:05:00Z"
          	 |,"Gate":"46"
          	 |,"Stand":"44R"
          	 |,"MaxPax":78
          	 |,"ActPax":51
          	 |,"TranPax":0
          	 |,"RunwayID":"05L"
          	 |,"BaggageReclaimId":"05"
          	 |,"FlightID":14710007
          	 |,"AirportID":"MAN"
          	 |,"Terminal":"T3"
          	 |,"ICAO":"BEE1272"
          	 |,"IATA":"BE1272"
          	 |,"Origin":"AMS"
          	 |,"SchDT":"2016-06-02T09:55:00Z"}]
        """.stripMargin.parseJson

      chromaFlightJson.convertTo[List[ChromaSingleFlight]] should beEqualTo(List(
        ChromaSingleFlight("Flybe",
          "On Chocks",
          "2016-06-02T10:55:00Z",
          "2016-06-02T10:55:00Z",
          "2016-06-02T11:01:00Z",
          "2016-06-02T11:05:00Z",
          "46",
          "44R",
          78,
          51,
          0,
          "05L",
          "05",
          14710007,
          "MAN",
          "T3",
          "BEE1272",
          "BE1272",
          "AMS",
          "2016-06-02T09:55:00Z")))
    }
  }
  isolated
}
