package services.arrivals

import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import org.specs2.mutable.Specification

import scala.util.{Failure, Success}

class EdiArrivalTerminalCSVSpec extends Specification {

  "Given a path to a CSV " >> {
    "Then I should get back a map of flightCode to terminal by month" >> {
      val url = getClass.getClassLoader.getResource("edi-terminal-map-fixture.csv")

      val expected = Success(Map(
        "TST0100" -> Map(
          "January" -> Terminal("A1"),
          "February" -> Terminal("A1"),
          "March" -> Terminal("A1"),
          "April" -> Terminal("A1"),
          "May" -> Terminal("A1"),
          "June" -> Terminal("A1"),
          "July" -> Terminal("A1"),
          "August" -> Terminal("A1"),
          "September" -> Terminal("A1"),
          "October" -> Terminal("A1"),
          "November" -> Terminal("A1"),
          "December" -> Terminal("A1")
        )
      ))
      val result = EdiArrivalTerminalCsvMapper(url)

      result === expected
    }
  }

  "Given an invalid path" >> {
    "Then I should get a Failure" >> {
      val url = getClass.getClassLoader.getResource("bad-filename.csv")


      val result = EdiArrivalTerminalCsvMapper(url)

      result.isInstanceOf[Failure[Map[String, Map[String, Terminal]]]]
    }
  }

  "Given a CSV string with 2 flights, and different terminals " >> {
    "Then I should get a containing both flights and their terminals" >> {
      val csvStringWith2Flights =
        """|code,Total Arrivals,A1_Winter,A2_Winter,A1_Summer,A2_Summer,January,February,March,April,May,June,July,August,September,October,November,December
           |TST100,1,100,0,0,0,A1,A1,A1,A1,A1,A1,A1,A1,A1,A1,A1,A1
           |TST200,1,100,0,0,0,A2,A2,A2,A2,A2,A2,A2,A2,A2,A2,A2,A2"""
          .stripMargin

      val expected = Map(
        "TST0100" -> Map(
          "January" -> Terminal("A1"),
          "February" -> Terminal("A1"),
          "March" -> Terminal("A1"),
          "April" -> Terminal("A1"),
          "May" -> Terminal("A1"),
          "June" -> Terminal("A1"),
          "July" -> Terminal("A1"),
          "August" -> Terminal("A1"),
          "September" -> Terminal("A1"),
          "October" -> Terminal("A1"),
          "November" -> Terminal("A1"),
          "December" -> Terminal("A1")
        ),
        "TST0200" -> Map(
          "January" -> Terminal("A2"),
          "February" -> Terminal("A2"),
          "March" -> Terminal("A2"),
          "April" -> Terminal("A2"),
          "May" -> Terminal("A2"),
          "June" -> Terminal("A2"),
          "July" -> Terminal("A2"),
          "August" -> Terminal("A2"),
          "September" -> Terminal("A2"),
          "October" -> Terminal("A2"),
          "November" -> Terminal("A2"),
          "December" -> Terminal("A2")
        )
      )

      val result = EdiArrivalTerminalCsvMapper.csvStringToMap(csvStringWith2Flights)

      result === expected
    }
  }

  "Given a CSV string with 1 flight, alternating terminals " >> {
    "Then I should get a map with matching terminal months" >> {
      val csvStringWithAlternatingTerminals =
        """|code,Total Arrivals,A1_Winter,A2_Winter,A1_Summer,A2_Summer,January,February,March,April,May,June,July,August,September,October,November,December
           |TST100,1,100,0,0,0,A2,A1,A2,A1,A2,A1,A2,A1,A2,A1,A2,A1"""
          .stripMargin

      val expected = Map(
        "TST0100" -> Map(
          "January" -> Terminal("A2"),
          "February" -> Terminal("A1"),
          "March" -> Terminal("A2"),
          "April" -> Terminal("A1"),
          "May" -> Terminal("A2"),
          "June" -> Terminal("A1"),
          "July" -> Terminal("A2"),
          "August" -> Terminal("A1"),
          "September" -> Terminal("A2"),
          "October" -> Terminal("A1"),
          "November" -> Terminal("A2"),
          "December" -> Terminal("A1")
        )
      )

      val result = EdiArrivalTerminalCsvMapper.csvStringToMap(csvStringWithAlternatingTerminals)

      result === expected
    }
  }




}
