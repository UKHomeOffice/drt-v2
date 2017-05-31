package services

import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalDate}
import org.specs2.mutable.{Specification, SpecificationLike}
import drt.shared.FlightsApi.{QueuePaxAndWorkLoads, TerminalName, TerminalQueuePaxAndWorkLoads}
import drt.shared.PassengerSplits.{SplitsPaxTypeAndQueueCount, VoyagePaxSplits}
import drt.shared.PaxTypes.{EeaMachineReadable, NonVisaNational}
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._
import org.slf4j.LoggerFactory
import services.WorkloadCalculatorTests.TestAirportConfig
import services.workloadcalculator.PaxLoadCalculator.{MillisSinceEpoch, PaxTypeAndQueueCount}
import services.workloadcalculator.{PaxLoadCalculator, WorkloadCalculator}
import services.SDate.implicits._

import scala.collection.immutable.IndexedSeq
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

        result === Some(SplitRatios(
          "CSV",
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
}

class WTFPaxSplitsFromCSVTests extends Specification {


  "Given a Flight Passenger Split" >> {
    "When we ask for workloads by terminal, then we should see the split applied" >> {
      val today = new DateTime()
      val csvSplitProvider = CSVPassengerSplitsProvider(Seq(s"BA1234,JHB,100,0,0,0,70,30,0,0,0,0,0,0,${today.dayOfWeek.getAsText},${today.monthOfYear.getAsText},STN,T1,SA"))
      val log = LoggerFactory.getLogger(getClass)

      def pcpArrivalTimeProvider(flight: ApiFlight): MilliDate = {
        log.error("don't call me!!!")
        MilliDate(SDate.parseString(flight.SchDT).millisSinceEpoch)
      }

      val workloadsCalculator = new WorkloadCalculator {
        def splitRatioProvider = csvSplitProvider.splitRatioProvider

        override def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = 3d

        def flightPaxTypeAndQueueCountsFlow(flight: ApiFlight): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] =
          PaxLoadCalculator.flightPaxFlowProvider(splitRatioProvider, pcpArrivalTimeProvider)(flight)
      }

      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.Future

      val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
      val flights = Future {
        List(apiFlight("BA1234", LocalDate.now().toString(formatter)))
      }

      val result: Future[TerminalQueuePaxAndWorkLoads[QueuePaxAndWorkLoads]] = workloadsCalculator.queueLoadsByTerminal(flights, PaxLoadCalculator.queueWorkAndPaxLoadCalculator)

      val act: TerminalQueuePaxAndWorkLoads[QueuePaxAndWorkLoads] = Await.result(result, 10 seconds)

      act("1").toList match {
        case List(("eeaDesk", (_, List(Pax(_, 0.3)))), ("eGate", (_, List(Pax(_, 0.7)))), ("nonEeaDesk", (_, List(Pax(_, 0.0))))) => true
      }
    }
  }


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


}


class EgatePercentagesFromPaxSplitsCsv extends Specification {

  val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")

  "DRT-4568 Given a Flight Passenger Split" >> {
    "When we ask for the egate percentage, we get a multiplier" >> {
      val egatePercentageStr = "70"
      val csvLines = Seq(s"""BA1234,JHB,100,0,0,0,$egatePercentageStr,30,0,0,0,0,0,0,Monday,January,STN,T1,SA""")
      val csvSplitProvider = CSVPassengerSplitsProvider(csvLines)

      val split: Option[SplitRatios] = csvSplitProvider.getFlightSplitRatios("BA1234", "Monday", "January")
      val egatePct = CSVPassengerSplitsProvider.egatePercentageFromSplit(split, 0)
      val expectedEgatePercentage = 0.7d
      expectedEgatePercentage === egatePct
    }
    "Given the flight isn't in the CSV files" +
      "When we request the egatePercentage, we get the default percentage " >> {
      val egatePercentageStr = "70"
      val csvLines = Seq(s"""BA1234,JHB,100,0,0,0,$egatePercentageStr,30,0,0,0,0,0,0,Monday,January,STN,T1,SA""")

      val csvSplitProvider = CSVPassengerSplitsProvider(csvLines)

      val split: Option[SplitRatios] = csvSplitProvider.getFlightSplitRatios("EZ199", "Monday", "January")
      val defaultPct = 0.6d
      val egatePct = CSVPassengerSplitsProvider.egatePercentageFromSplit(split, defaultPct)

      val expectedEgatePercentage = 0.6d
      expectedEgatePercentage === egatePct
    }

    "We can convert a VoyagePassengerInfo (split from DQ API) into an ApiSplit where we apply a percentage calculation to the" +
      "eeaDesk, diverting that percentage to eGate" >> {
      val vps = VoyagePaxSplits("STN", "BA", "978", 100, SDate(2017, 10, 1, 10, 30), List(
        SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100),
        SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 20)
      ))
      val converted = vps.copy(paxSplits = List(
        SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 30),
        SplitsPaxTypeAndQueueCount(EeaMachineReadable, EGate, 70),
        SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 20)
      ))

      converted.paxSplits.toSet === CSVPassengerSplitsProvider.applyEgates(vps, 0.7).paxSplits.toSet

    }
    "Given an initial paxCount where the percentage would not be a round number, we put any remainder in the eeaDesk" +
      "so a 70% split on 99 people sends 30 to eeaDesk and 69 to eGates" >> {
      val vps = VoyagePaxSplits("STN", "BA", "978", 100, SDate(2017, 10, 1, 10, 30), List(
        SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 99),
        SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 20)
      ))
      val converted = vps.copy(paxSplits = List(
        SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 30),
        SplitsPaxTypeAndQueueCount(EeaMachineReadable, EGate, 69),
        SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 20)
      ))

      converted.paxSplits.toSet === CSVPassengerSplitsProvider.applyEgates(vps, 0.7).paxSplits.toSet

    }
  }



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


}
