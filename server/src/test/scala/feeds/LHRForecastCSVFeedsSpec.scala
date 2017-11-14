package feeds

import drt.shared.Arrival
import drt.shared.FlightsApi.TerminalName
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.specs2.mutable.Specification
import services.SDate

import scala.util.{Success, Try}

class LHRForecastCSVFeedsSpec extends Specification {
  
  def parseCSV(csvContent: String, terminalName: TerminalName) = {
    val flightEntries = csvContent
      .split("\n")
      .drop(3)

    val arrivalEntries = flightEntries
      .map(_.split(",").toList)
      .filter(_.length == 8)
      .filter(_ (4) == "INTERNATIONAL")

    def lhrFieldsToArrivalForTerminal: (List[String]) => Try[Arrival] = lhrFieldsToArrival(terminalName)
    val arrivals = arrivalEntries
      .map(lhrFieldsToArrivalForTerminal)
      .collect { case Success(a) => a }
      .toList

    arrivals
  }
  val pattern: DateTimeFormatter = DateTimeFormat.forPattern("dd/MM/YYYY HH:mm")

  def parseDateTime(dateString: String): DateTime = pattern.parseDateTime(dateString)

  def lhrFieldsToArrival(terminalName: TerminalName)(fields: List[String]): Try[Arrival] = {
    Try {
      Arrival(
        Operator = "",
        Status = "Port Forecast",
        EstDT = "",
        ActDT = "",
        EstChoxDT = "",
        ActChoxDT = "",
        Gate = "",
        Stand = "",
        MaxPax = 0,
        ActPax = fields(5).toInt,
        TranPax = fields(7).toInt,
        RunwayID = "",
        BaggageReclaimId = "",
        FlightID = 0,
        AirportID = "LHR",
        Terminal = terminalName,
        rawICAO = fields(2).replace(" ", ""),
        rawIATA = fields(2).replace(" ", ""),
        Origin = fields(3),
        SchDT = SDate(parseDateTime(fields(1))).toISOString(),
        Scheduled = SDate(parseDateTime(fields(1))).millisSinceEpoch,
        PcpTime = 0,
        None
      )
    }
  }

  "When parsing a CSV file for LHR Forecast" >> {

    "Given two rows then I should get back two flights" >> {
      val csv =
        """,,,,,,,
          |,,,,,,,
          |,Scheduled Date,Flight Number,Airport,Int or Dom,Total,Direct,Transfer
          |,31/10/2017 16:45,LH 0914,FRA,INTERNATIONAL,23,17,6
          |,31/10/2017 16:35,LX 0348,GVA,INTERNATIONAL,73,71,1""".stripMargin

      val expected = List(
        Arrival(
          "", "Port Forecast", "", "", "", "", "", "", 0, 23, 6, "", "", 0, "LHR", "T2", "LH0914", "LH0914", "FRA",
          SDate("2017-10-31T16:45").toISOString(), SDate("2017-10-31T16:45").millisSinceEpoch, 0, None
        ),
        Arrival("", "Port Forecast", "", "", "", "", "", "", 0, 73, 1, "", "", 0, "LHR", "T2", "LX0348", "LX0348", "GVA",
          SDate("2017-10-31T16:35").toISOString(), SDate("2017-10-31T16:35").millisSinceEpoch, 0, None
        )
      )

      val result = parseCSV(csv, "T2")

      result === expected
    }
  }

  "When parsing a CSV file for LHR Forecast" >> {

    "Given a flight marked as domestic, that flight should not appear in the list" >> {
      val csv =
        """,,,,,,,
          |,,,,,,,
          |,Scheduled Date,Flight Number,Airport,Int or Dom,Total,Direct,Transfer
          |,31/10/2017 16:45,LH 0914,FRA,INTERNATIONAL,23,17,6
          |,31/10/2017 16:35,LX 0348,GVA,DOMESTIC,73,71,1""".stripMargin

      val expected = List(
        Arrival(
          "", "Port Forecast", "", "", "", "", "", "", 0, 23, 6, "", "", 0, "LHR", "T2", "LH0914", "LH0914", "FRA",
          SDate("2017-10-31T16:45").toISOString(), SDate("2017-10-31T16:45").millisSinceEpoch, 0, None
        )
      )

      val result = parseCSV(csv, "T2")

      result === expected
    }
  }
}
