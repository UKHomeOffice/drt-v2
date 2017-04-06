package services

import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalDate}
import org.specs2.mutable.SpecificationLike
import drt.shared.FlightsApi.TerminalName
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._

import scala.concurrent.Await
import scala.concurrent.duration._

class PaxSplitsFromCSVTests extends SpecificationLike {

  def apiFlight(iataFlightCode: String, schDT: String): ApiFlight =
    ApiFlight(
      Operator = "",
      Status = "",
      EstDT = "",
      ActDT = "",
      EstChoxDT = "",
      ActChoxDT = "",
      Gate = "",
      Stand = "",
      MaxPax = 1,
      ActPax = 0,
      TranPax = 0,
      RunwayID = "",
      BaggageReclaimId = "",
      FlightID = 2,
      AirportID = "STN",
      Terminal = "1",
      rawICAO = "",
      rawIATA = iataFlightCode,
      Origin = "",
      PcpTime = 0,
      SchDT = schDT
    )

  import CsvPassengerSplitsReader._

  "Split ratios from CSV" >> {
    "Given a path to the CSV file" >> {
      "Then I should be able to parse the file" >> {
        val expected = Seq(
          FlightPaxSplit("BA1234", "JHB", 97, 0, 2, 1, 70, 30, 100, 0, 100, 0, 100, 0, "Sunday", "January", "STN", "T1", "SA"),
          FlightPaxSplit("BA1234", "JHB", 97, 0, 2, 1, 70, 30, 100, 0, 100, 0, 100, 0, "Monday", "January", "STN", "T1", "SA")
        )

        val splitsLines = Seq(
          "BA1234,JHB,97,0,2,1,70,30,100,0,100,0,100,0,Sunday,January,STN,T1,SA",
          "BA1234,JHB,97,0,2,1,70,30,100,0,100,0,100,0,Monday,January,STN,T1,SA"
        )

        val rows = flightPaxSplitsFromLines(splitsLines)

        rows.toList === expected
      }
    }

    "Given a pax splits CSV has been loaded" >> {
      "When I query the pax splits for a flight on a date, " +
        "then I should get the correct split back" >> {

        val splitsProvider = CSVPassengerSplitsProvider(Seq(
          "BA1234,JHB,97,0,2,1,70,30,100,0,100,0,100,0,Sunday,January,STN,T1,SA"
        ))

        val result = splitsProvider.splitRatioProvider(apiFlight("BA1234", "2017-01-01"))

        result === Some(SplitRatios(SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk), 0.291),
          SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EGate), 0.6789999999999999),
          SplitRatio(PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk), 0.0),
          SplitRatio(PaxTypeAndQueue(PaxTypes.VisaNational, Queues.NonEeaDesk), 0.01),
          SplitRatio(PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk), 0.02)))
      }

      "When I query the pax splits for a non existent flight, " +
        "then I should get None" >> {

        val splitsProvider = CSVPassengerSplitsProvider {
          Seq()
        }

        val result = splitsProvider.splitRatioProvider(apiFlight("XXXX", "2017-01-01"))

        result === None
      }
    }

    "Given a FlightPaxSplit" >> {
      "When I ask for the SplitRatios then I should get a list of each split type for a flight" >> {
        val row = FlightPaxSplit("BA1234", "JHB", 97, 0, 2, 1, 70, 30, 100, 0, 100, 0, 100, 0, "Sunday", "January", "STN", "T1", "SA")

        val result = splitRatioFromFlightPaxSplit(row)

        result === List(SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk), 0.291),
          SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EGate), 0.6789999999999999),
          SplitRatio(PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk), 0.0),
          SplitRatio(PaxTypeAndQueue(PaxTypes.VisaNational, Queues.NonEeaDesk), 0.01),
          SplitRatio(PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk), 0.02))
      }
    }
  }

  "Given a Flight Passenger Split" >> {
    "When we ask for workloads by terminal, then we should see the split applied" >> {
      val today = new DateTime()
      val csvSplitProvider = CSVPassengerSplitsProvider(Seq(s"BA1234,JHB,100,0,0,0,70,30,0,0,0,0,0,0,${today.dayOfWeek.getAsText},${today.monthOfYear.getAsText},STN,T1,SA"))

      val workloadsCalculator = new WorkloadsCalculator {
        def splitRatioProvider = csvSplitProvider.splitRatioProvider

        def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = 3d
      }

      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.Future

      val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
      val flights = Future {
        List(apiFlight("BA1234", LocalDate.now().toString(formatter)))
      }

      val result: Future[workloadsCalculator.TerminalQueuePaxAndWorkLoads] = workloadsCalculator.workAndPaxLoadsByTerminal(flights)

      val act: workloadsCalculator.TerminalQueuePaxAndWorkLoads = Await.result(result, 10 seconds)

      act("1").toList match {
        case List(("eeaDesk", (_, List(Pax(_, 0.3)))), ("eGate", (_, List(Pax(_, 0.7)))), ("nonEeaDesk", (_, List(Pax(_, 0.0))))) => true
      }
    }
  }
}
