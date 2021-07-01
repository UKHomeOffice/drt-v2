package controllers.model

import drt.shared.PortCode
import org.specs2.mutable.Specification
import services.SDate
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.enrichAny
import spray.json._

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

    "I should be able to convert it to json with additional fields" >> {
      import RedListCountsJsonFormats._
      val json = """[{
                   |  "departurePort": "FRA",
                   |  "embarkPort": "SJO",
                   |  "flightCode": "BA0001",
                   |  "paxCount": 10,
                   |  "portCode": "LHR",
                   |  "scheduled": 1624449600000
                   |}, {
                   |  "flightCode": "ZZ0072",
                   |  "paxCount": 20,
                   |  "portCode": "LHR",
                   |  "scheduled": 1624462200000
                   |}]""".stripMargin.parseJson
      val recovered = json.convertTo[RedListCounts]

      recovered === redListCounts
    }
  }
}

//[{"departurePort": "MAD", "embarkPort": "PTY", "flightCode": "IB3166", "paxCount": 1, "portCode": "LHR", "scheduled": 1625328300000, "scheduledDeparture": 1625323500000}, {"departurePort": "AMS", "embarkPort": "JNB", "flightCode": "KL1021", "paxCount": 1, "portCode": "LHR", "scheduled": 1625238900000, "scheduledDeparture": 1625237700000}, {"departurePort": "CDG", "embarkPort": "JNB", "flightCode": "AF1680", "paxCount": 26, "portCode": "LHR", "scheduled": 1625295300000, "scheduledDeparture": 1625293800000}, {"departurePort": "SIN", "embarkPort": "CRK", "flightCode": "SQ0306", "paxCount": 1, "portCode": "LHR", "scheduled": 1625208300000, "scheduledDeparture": 1625184600000}, {"departurePort": "FRA", "embarkPort": "SJO", "flightCode": "LH0914", "paxCount": 1, "portCode": "LHR", "scheduled": 1625240400000, "scheduledDeparture": 1625238000000}, {"departurePort": "FRA", "embarkPort": "BOG", "flightCode": "LH0922", "paxCount": 1, "portCode": "LHR", "scheduled": 1625260200000, "scheduledDeparture": 1625257800000}, {"departurePort": "BRU", "embarkPort": "EBB", "flightCode": "SN2093", "paxCount": 1, "portCode": "LHR", "scheduled": 1625217300000, "scheduledDeparture": 1625215800000}, {"departurePort": "FRA", "embarkPort": "NBO", "flightCode": "LH0900", "paxCount": 2, "portCode": "LHR", "scheduled": 1625211600000, "scheduledDeparture": 1625209200000}, {"departurePort": "AMS", "embarkPort": "NBO", "flightCode": "KL1007", "paxCount": 5, "portCode": "LHR", "scheduled": 1625212800000, "scheduledDeparture": 1625211600000}]
