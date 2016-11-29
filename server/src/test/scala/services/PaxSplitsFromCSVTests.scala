package services

import java.net.URL

import controllers.FlightStateTests
import org.joda.time.{DateTime, LocalDate}
import org.joda.time.format.DateTimeFormat
import org.specs2.mutable.SpecificationLike
import services.inputfeeds.CrunchTests.TestContext
import services.workloadcalculator.PassengerQueueTypes.{PaxTypes, Queues}
import services.workloadcalculator.PaxLoadAt.PaxTypeAndQueue
import services.workloadcalculator.SplitRatio
import spatutorial.shared.{ApiFlight, Pax, WL}

import scala.concurrent.Await
import scala.util.Random
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
      ICAO = "",
      IATA = iataFlightCode,
      Origin = "",
      PcpTime = 0,
      SchDT = schDT
    )

  import PassengerSplitsCSVReader._

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

        rows.toList == expected
      }
    }

    "Given a pax splits CSV has been loaded" >> {
      "When I query the pax splits for a flight on a date, " +
        "then I should get the correct split back" >> {

        val splitsProvider = new CSVPassengerSplitsProvider {
          override def flightPassengerSplitLines: Seq[String] = Seq(
            "BA1234,JHB,97,0,2,1,70,30,100,0,100,0,100,0,Sunday,January,STN,T1,SA"
        )
        }

        val result = splitsProvider.splitRatioProvider(apiFlight("BA1234", "2017-01-01"))

        val expected = List(SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.291),
          SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 0.6789999999999999),
          SplitRatio(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 0.0),
          SplitRatio(PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk), 0.01),
          SplitRatio(PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk), 0.02))

        result == expected
      }

      "When I query the pax splits for a non existent flight, " +
        "then I should get the default split back" >> {

        val splitsProvider = new CSVPassengerSplitsProvider {
          override def flightPassengerSplitLines: Seq[String] = Seq()
        }

        val result = splitsProvider.splitRatioProvider(apiFlight("XXXX", "2017-01-01"))

        val expected = new DefaultPassengerSplitRatioProvider {}.splitRatioProvider(apiFlight("XXXX", "2017-01-01"))

        result == expected
      }
    }

        "Given a FlightPaxSplit" >> {
          "When I ask for the SplitRations then I should get a list of each split type for a flight" >> {
            val row = FlightPaxSplit("BA1234", "JHB", 97, 0, 2, 1, 70, 30, 100, 0, 100, 0, 100, 0, "Sunday", "January", "STN", "T1", "SA")

            val expected = List(SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.291),
              SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 0.6789999999999999),
              SplitRatio(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 0.0),
              SplitRatio(PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk), 0.01),
              SplitRatio(PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk), 0.02))

            val result = splitRatioFromFlightPaxSplit(row)

            result == expected
          }
        }
  }

    "Given a Fligth Passenger Split" >> {
      "When we ask for workloads by terminal, then we should see the split applied" >> {
        val workloadsCalculator = new WorkloadsCalculator with CSVPassengerSplitsProvider {

          val today = new DateTime()
          override def flightPassengerSplitLines: Seq[String] = Seq(
            s"BA1234,JHB,100,0,0,0,70,30,0,0,0,0,0,0,${today.dayOfWeek.getAsText},${today.monthOfYear.getAsText},STN,T1,SA"
          )
        }

        import scala.concurrent.Future

        import scala.concurrent.ExecutionContext.Implicits.global

        val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
        val flights = Future { List(apiFlight("BA1234", LocalDate.now().toString(formatter))) }

        val result: Future[workloadsCalculator.TerminalQueueWorkloads] = workloadsCalculator.getWorkloadsByTerminal(flights)

        val expected = Map("1" -> Map(
          "eeaDesk" -> (List(WL(1480377600000L,0.09999999999999999)),List(Pax(1480377600000L,0.3))),
          "eGate" -> (List(WL(1480377600000L,0.4083333333333333)),List(Pax(1480377600000L,0.7))),
          "nonEeaDesk" -> (List(WL(1480377600000L,0.0)),List(Pax(1480377600000L,0.0)))))

        val act: workloadsCalculator.TerminalQueueWorkloads = Await.result(result, 10 seconds)

        act == expected
      }
    }
}
