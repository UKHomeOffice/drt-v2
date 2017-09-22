package passengersplits.csv

import controllers.ArrivalGenerator.apiFlight
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._
import org.specs2.mutable.SpecificationLike
import services.{CSVPassengerSplitsProvider, CsvPassengerSplitsReader}

class PaxSplitsFromCSVTests extends SpecificationLike {
  val CsvSplitSource = "Historical"

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
        "DRT-4598 And the fast track is 0  " +
        "then I should get the correct split back" +
        "and the fast track is filtered out" >> {

        val splitsProvider = CSVPassengerSplitsProvider(Seq(
          "BA1234,JHB,97,0,2,1,70,30,100,0,100,0,100,0,Sunday,January,STN,T1,SA"
        ))

        val result = splitsProvider.splitRatioProvider(apiFlight(flightId = 1, iata = "BA1234", schDt = "2017-01-01"))

        result === Some(SplitRatios(
          CsvSplitSource,
          SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk), 0.291),
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

        val result = splitsProvider.splitRatioProvider(apiFlight(flightId = 1, iata = "XXXX", schDt = "2017-01-01"))

        result === None
      }
    }

    "DRT-4598 Given a FlightPaxSplit " +
      "And the fast-track is zero " +
      "When I ask for the SplitRatios then I should get a list of each split type for a flight" >> {
        "And there will be no fast-track entry" >> {
          val row = FlightPaxSplit("BA1234", "JHB", 97, 0, 2, 1, 70, 30, 100, 0, 100, 0, 100, 0, "Sunday", "January", "STN", "T1", "SA")

          val result = splitRatioFromFlightPaxSplit(row)

          result === List(SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk), 0.291),
            SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EGate), 0.6789999999999999),
            SplitRatio(PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk), 0.0),
            SplitRatio(PaxTypeAndQueue(PaxTypes.VisaNational, Queues.NonEeaDesk), 0.01),
            SplitRatio(PaxTypeAndQueue(PaxTypes.VisaNational, Queues.FastTrack), 0),
            SplitRatio(PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.FastTrack), 0),
            SplitRatio(PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk), 0.02))
        }
      }

    "DRT-4598 Given a FlightPaxSplit " +
      "And the fast-track is zero " +
      "When I ask for the SplitRatios then I should get a list of each split type for a flight" >> {
        "And there will be no fast-track entry" >> {
          val row = FlightPaxSplit("BA1234", "JHB", 97, 0, 2, 1, 70, 30, 100, 40, 60, 30, 70, 0, "Sunday", "January", "STN", "T1", "SA")

          val result = splitRatioFromFlightPaxSplit(row)

          result === List(SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk), 0.291),
            SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EGate), 0.6789999999999999),
            SplitRatio(PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk), 0.0),
            SplitRatio(PaxTypeAndQueue(PaxTypes.VisaNational, Queues.NonEeaDesk), 0.006999999999999999),
            SplitRatio(PaxTypeAndQueue(PaxTypes.VisaNational, Queues.FastTrack), 0.003d),
            SplitRatio(PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.FastTrack), 0.008d),
            SplitRatio(PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk), 0.012))
        }
      }

  }
/*
  "Given a Flight Passenger Split" >> {
    "When we ask for workloads by terminal, then we should see the split applied" >> {
      val today = new DateTime(2017, 1, 1, 14, 0)
      val csvSplitProvider = CSVPassengerSplitsProvider(Seq(s"BA1234,JHB,100,0,0,0,70,30,0,0,0,0,0,0,${today.dayOfWeek.getAsText},${today.monthOfYear.getAsText},STN,T1,SA"))
      val log = LoggerFactory.getLogger(getClass)

      def pcpArrivalTimeProvider(flight: Arrival): MilliDate = {
        log.error("don't call me!!!")
        MilliDate(SDate.parseString(flight.SchDT).millisSinceEpoch)
      }

      val workloadsCalculator = new WorkloadCalculator {
        def splitRatioProvider = csvSplitProvider.splitRatioProvider

        override def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = 3d

        def flightPaxTypeAndQueueCountsFlow(flight: Arrival): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] =
          PaxLoadCalculator.flightPaxFlowProvider(splitRatioProvider, BestPax.bestPax)(flight)
      }

      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.Future

      val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
      val flights = Future {
        List(apiFlight(flightId = 1, iata = "BA1234", actPax = 1, airportId = "STN", terminal = "1", schDt = today.toString(formatter)))
      }

      val result: Future[PortPaxAndWorkLoads[QueuePaxAndWorkLoads]] = workloadsCalculator.queueLoadsByTerminal(flights, PaxLoadCalculator.queueWorkAndPaxLoadCalculator)

      val act: PortPaxAndWorkLoads[QueuePaxAndWorkLoads] = Await.result(result, 10 seconds)

      val actList = act("1").toList
      val eGateSplit = actList.find(split => split._1 == "eGate")
      val eeaDeskSplit = actList.find(split => split._1 == "eeaDesk")
      (eGateSplit.get._2._2.head.pax, eeaDeskSplit.get._2._2.head.pax) === (0.7, 0.3)
    }
  }
*/
  "Given a dodgy peice of data in a CSV file" >> {
    "Then I should get back the splits in the correctly formatted lines anyway" >> {
      val expected = Seq(
        FlightPaxSplit("BA1234", "JHB", 97, 0, 2, 1, 70, 30, 100, 0, 100, 0, 100, 0, "Monday", "January", "STN", "T1", "SA")
      )

      val splitsLines = Seq(
        "Missing,some,data,0,2,1,70,30,100,0,,0,100,0,Sunday,January,STN,T1,SA",
        "BA1234,JHB,97,0,2,1,70,30,100,0,100,0,100,0,Monday,January,STN,T1,SA"
      )

      val rows = flightPaxSplitsFromLines(splitsLines)

      rows.toList === expected
    }
  }
}




