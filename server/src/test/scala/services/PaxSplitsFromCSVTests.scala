package services

import java.net.URL

import controllers.FlightStateTests
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

        val rows = parseCSV(getClass.getResource("/passenger-splits-fixture.csv"))

        rows.take(2) == expected
      }
    }

    "Given a pax splits CSV has been loaded" >> {
      "When I query the pax splits for a flight on a date, " +
        "then I should get the correct split back" >> {

        val splitsProvider = new PassengerSplitsCSVProvider {
          override def csvSplitUrl: String = getClass.getResource("/passenger-splits-fixture.csv").toString
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

        val splitsProvider = new PassengerSplitsCSVProvider {
          override def csvSplitUrl: String = getClass.getResource("/passenger-splits-fixture.csv").toString
        }

        val result = splitsProvider.splitRatioProvider(apiFlight("XXXX", "2017-01-01"))

        val expected = new DefaultPassengerSplitRatioProvider {}.splitRatioProvider(apiFlight("XXXX", "2017-01-01"))

        result == expected
      }
    }

    "Given a CSV containing pax splits" >> {
      "When I parse the CSV row then I should get a list of each split type for a flight" >> {
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

//  "Terminal workloads from CSV" >> {
//    "Something" >> {
//      val workloadsCalculator = new WorkloadsCalculator with PassengerSplitsCSVProvider {
//        override def csvSplitUrl: String = getClass.getResource("/passenger-splits-fixture.csv").toString
//      }
//
//      import scala.concurrent.Future
//
//      import scala.concurrent.ExecutionContext.Implicits.global
//
//      val flights = Future { List(apiFlight("BA1234", "2016-11-28")) }
//
//      val result: Future[workloadsCalculator.TerminalQueueWorkloads] = workloadsCalculator.getWorkloadsByTerminal(flights)
//
//      val expected = Map(1 -> Map(
//        "eeaDesk" -> (List(WL(1480291200000L,0.09699999999999999)),List(Pax(1480291200000L,0.291))),
//        "eGate" -> (List(WL(1480291200000L,0.39608333333333334)),List(Pax(1480291200000L,0.6789999999999999))),
//        "nonEeaDesk" -> (List(WL(1480291200000L,0.041)),List(Pax(1480291200000L,0.03)))))
//
//
//      val act = Await.result(result, 10 seconds)
//
//      println(s"Hello $act")
//      act == expected
//    }
//  }

//  val rows = parseCSV(getClass.getResource("/passenger-splits-fixture.csv"))
//
//  val splitsProvider = new PassengerSplitsCSVProvider {
//    override def csvSplitUrl: String = "file:///Users/beneppel/Downloads/STN2006Final.csv"
//  }
//
//  val flightCodes = parseCSV(new URL("file:///Users/beneppel/Downloads/STN2006Final.csv")).map(row => {
//    row.flightCode
//  }).toSet
//
//  "performance test" >> {
//
//    println(flightCodes.size)
//
//    (1 to 100).map( _ => {
//      val randomFlightCodes = util.Random.shuffle(flightCodes)
//      randomFlightCodes.map(flightCode => {
//        splitsProvider.splitRatioProvider(apiFlight(flightCode, "2017-" + (Random.nextInt(11) + 1) + "-" + (Random.nextInt(27) + 1)))
//      })
//    })
//    true
//  }
}
