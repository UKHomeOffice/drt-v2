package controllers.model

import drt.shared.PortCode
import org.specs2.mutable.Specification
import services.SDate
import spray.json.enrichAny

class RedListCountsJsonFormatsSpec extends Specification {
  "Given a RedListCounts" >> {
    val redListCounts = RedListCounts(Iterable(
      RedListCount("BA0001", PortCode("LHR"), SDate("2021-06-23T12:00"), 10),
      RedListCount("ZZ0072", PortCode("LHR"), SDate("2021-06-23T15:30"), 20),
    ))

    "I should be able to convert it to json in the expected format" >> {
      import RedListCountsJsonFormats._
      val json = redListCounts.toJson
      json.prettyPrint ===
        """[{
          |  "flightCode": "BA0001",
          |  "paxCount": 10,
          |  "portCode": "LHR",
          |  "scheduled": 1624449600000
          |}, {
          |  "flightCode": "ZZ0072",
          |  "paxCount": 20,
          |  "portCode": "LHR",
          |  "scheduled": 1624462200000
          |}]""".stripMargin
    }

    "I should be able to convert it to json and back without any data loss" >> {
      import RedListCountsJsonFormats._
      val json = redListCounts.toJson
      val recovered = json.convertTo[RedListCounts]

      recovered === redListCounts
    }
  }
}
