package services.`export`

import controllers.ArrivalGenerator
import controllers.application.exports.CsvFileStreaming
import drt.shared.PortCode
import drt.shared.Terminals.T1
import org.specs2.mutable.Specification
import services.SDate
import services.exports.Exports.passengerFlightsFilter
import services.graphstages.Crunch

class ExportsSpec extends Specification {
  "Given a start date of midnight BST 2020-06-24 and an end date of 1 minute before midnight BST (24 hours)" >> {
    "When I ask for a filename for the export" >> {
      "I should get a file name with just the start date 2020-06-24" >> {
        val startDate = SDate("2020-06-24T00:00", Crunch.europeLondonTimeZone)
        val endDate = startDate.addDays(1).addMinutes(-1)
        val result = CsvFileStreaming.makeFileName("mysubject", T1, startDate, endDate, PortCode("LHR"))

        val expected = "LHR-T1-mysubject-2020-06-24"

        result === expected
      }
    }
  }

  "Given a start date of midnight UTC 2020-01-01 and an end date of 1 minute before midnight UTC (24 hours)" >> {
    "When I ask for a filename for the export" >> {
      "I should get a file name with just the start date" >> {
        val startDate = SDate("2020-01-01T00:00", Crunch.europeLondonTimeZone)
        val endDate = startDate.addDays(1).addMinutes(-1)
        val result = CsvFileStreaming.makeFileName("mysubject", T1, startDate, endDate, PortCode("LHR"))

        val expected = "LHR-T1-mysubject-2020-01-01"

        result === expected
      }
    }
  }

  "Given a start date of midnight BST 2020-06-24 and an end date of 1 minute before midnight the following day BST (2 days)" >> {
    "When I ask for a filename for the export" >> {
      "I should get a file name with the start date 2020-06-24 and end date of 2020-06-25" >> {
        val startDate = SDate("2020-06-24T00:00", Crunch.europeLondonTimeZone)
        val endDate = startDate.addDays(2).addMinutes(-1)
        val result = CsvFileStreaming.makeFileName("mysubject", T1, startDate, endDate, PortCode("LHR"))

        val expected = "LHR-T1-mysubject-2020-06-24-to-2020-06-25"

        result === expected
      }
    }
  }

  "Given a start date of midnight UTC 2020-01-01 and an end date of 1 minute before midnight the following day UTC (2 days)" >> {
    "When I ask for a filename for the export" >> {
      "I should get a file name with the start date 2020-01-01 and end date of 2020-01-02" >> {
        val startDate = SDate("2020-01-01T00:00", Crunch.europeLondonTimeZone)
        val endDate = startDate.addDays(2).addMinutes(-1)
        val result = CsvFileStreaming.makeFileName("mysubject", T1, startDate, endDate, PortCode("LHR"))

        val expected = "LHR-T1-mysubject-2020-01-01-to-2020-01-02"

        result === expected
      }
    }
  }

  "Given passenger flight filter" >> {
    "When flights in arrival are not having service type" >> {
      "filter should include the flights with no service type" >> {
        val flights = List(
          ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("ABC"), schDt = "2019-01-01T00:35", actPax = Option(200), maxPax = Some(200)),
          ArrivalGenerator.arrival(iata = "BA0002", terminal = T1, origin = PortCode("ABC"), schDt = "2019-01-03T00:35", actPax = Option(400), maxPax = Some(400))
        )

        val result = flights.filter(passengerFlightsFilter)

        result.size mustEqual 2
      }
    }

    "When flights in arrival are having service type and Zero load factor values" >> {
      "filter should exclude the flights with passenger service type and zero loadFactor" >> {
        val flights = List(
          ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("ABC"), schDt = "2019-01-01T00:35", actPax = Option(200), maxPax = Some(200), serviceType = Some("J"), loadFactor = None),
          ArrivalGenerator.arrival(iata = "BA0002", terminal = T1, origin = PortCode("ABC"), schDt = "2019-01-03T00:35", actPax = Option(400), maxPax = Some(400), serviceType = Some("Q"), loadFactor = Some(0.0))
        )

        val result = flights.filter(passengerFlightsFilter)

        result.size mustEqual 0
      }
    }

    "When flights in arrival are having service type and non-zero load factor values" >> {
      "filter should include the flights with passenger service type and non-zero loadFactor" >> {
        val flights = List(
          ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("ABC"), schDt = "2019-01-01T00:35", actPax = Option(200), maxPax = Some(200), serviceType = Some("J"), loadFactor = Some(0.0)),
          ArrivalGenerator.arrival(iata = "BA0002", terminal = T1, origin = PortCode("ABC"), schDt = "2019-01-03T00:35", actPax = Option(400), maxPax = Some(400), serviceType = Some("Q"), loadFactor = Some(1.0))
        )

        val result = flights.filter(passengerFlightsFilter)

        result.size mustEqual 1
        result.head.flightCodeString mustEqual "BA0002"
      }
    }

    "When flights in arrival are having service type and non-zero load factor values and zero max passenger" >> {
      "filter should exclude the flights with passenger service type and non-zero loadFactor and zero max passenger" >> {
        val flights = List(
          ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("ABC"), schDt = "2019-01-01T00:35", actPax = Option(200), maxPax = Some(0), serviceType = Some("J"), loadFactor = Some(1.0)),
          ArrivalGenerator.arrival(iata = "BA0002", terminal = T1, origin = PortCode("ABC"), schDt = "2019-01-03T00:35", actPax = Option(400), maxPax = Some(0), serviceType = Some("Q"), loadFactor = Some(1.0))
        )

        val result = flights.filter(passengerFlightsFilter)

        result.size mustEqual 0
      }
    }


    "When flights in arrival are having non-passenger service type and non-zero load factor values and non-zero max passenger" >> {
      "filter should exclude the flights with non-passenger service type and non-zero loadFactor and non-zero max passenger" >> {
        val flights = List(
          ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("ABC"), schDt = "2019-01-01T00:35", actPax = Option(200), maxPax = Some(200), serviceType = Some("P"), loadFactor = Some(1.0)),
          ArrivalGenerator.arrival(iata = "BA0002", terminal = T1, origin = PortCode("ABC"), schDt = "2019-01-03T00:35", actPax = Option(400), maxPax = Some(400), serviceType = Some("Q"), loadFactor = Some(1.0))
        )

        val result = flights.filter(passengerFlightsFilter)

        result.size mustEqual 1
        result.head.flightCodeString mustEqual "BA0002"
      }
    }
  }
}
