package controllers.model

import drt.shared.RedListPassengers
import org.specs2.mutable.Specification
import services.SDate
import spray.json._
import uk.gov.homeoffice.drt.ports.PortCode

class RedListCountsJsonFormatsSpec extends Specification {
  "Given a RedListCounts" >> {
    val redListCounts = RedListCounts(Iterable(
      RedListPassengers("BA0001", PortCode("LHR"), SDate("2021-06-23T12:00"), List("TUN/IOI/0309E/173", "PHL/IOI/0309E/174", "THA/IOI/0309E/175", "KEN/IOI/0309E/203", "SYC/IOI/0309E/204", "BGD/IOI/0309E/205")),
      RedListPassengers("ZZ0072", PortCode("LHR"), SDate("2021-06-23T15:30"), List("TUN/IOI/0309E/173", "PHL/IOI/0309E/174", "THA/IOI/0309E/175", "KEN/IOI/0309E/203", "SYC/IOI/0309E/204", "BGD/IOI/0309E/205")),
    ))

    "I should be able to convert it to json in the expected format" >> {
      import RedListCountsJsonFormats._
      val json = redListCounts.toJson
      json.prettyPrint ===
        """[{
          |  "flightCode": "BA0001",
          |  "portCode": "LHR",
          |  "scheduled": 1624449600000,
          |  "urns": ["TUN/IOI/0309E/173", "PHL/IOI/0309E/174", "THA/IOI/0309E/175", "KEN/IOI/0309E/203", "SYC/IOI/0309E/204", "BGD/IOI/0309E/205"]
          |}, {
          |  "flightCode": "ZZ0072",
          |  "portCode": "LHR",
          |  "scheduled": 1624462200000,
          |  "urns": ["TUN/IOI/0309E/173", "PHL/IOI/0309E/174", "THA/IOI/0309E/175", "KEN/IOI/0309E/203", "SYC/IOI/0309E/204", "BGD/IOI/0309E/205"]
          |}]""".stripMargin
    }

    "I should be able to convert it to json and back without any data loss" >> {
      import RedListCountsJsonFormats._
      val json = redListCounts.toJson
      val recovered = json.convertTo[RedListCounts]

      recovered === redListCounts
    }

    "I should be able to convert it to json with additional fields safely ignored" >> {
      import RedListCountsJsonFormats._
      val json =
        """[{
          |  "departurePort": "FRA",
          |  "embarkPort": "SJO",
          |  "flightCode": "BA0001",
          |  "urns": ["TUN/IOI/0309E/173", "PHL/IOI/0309E/174", "THA/IOI/0309E/175", "KEN/IOI/0309E/203", "SYC/IOI/0309E/204", "BGD/IOI/0309E/205"],
          |  "portCode": "LHR",
          |  "scheduled": 1624449600000
          |}, {
          |  "flightCode": "ZZ0072",
          |  "urns": ["TUN/IOI/0309E/173", "PHL/IOI/0309E/174", "THA/IOI/0309E/175", "KEN/IOI/0309E/203", "SYC/IOI/0309E/204", "BGD/IOI/0309E/205"],
          |  "portCode": "LHR",
          |  "scheduled": 1624462200000
          |}]""".stripMargin.parseJson
      val recovered = json.convertTo[RedListCounts]

      recovered === redListCounts
    }
  }
}
