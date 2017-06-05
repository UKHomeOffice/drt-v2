package passengersplits.csv

import drt.shared.ApiFlight
import drt.shared.PassengerSplits.{SplitsPaxTypeAndQueueCount, VoyagePaxSplits}
import drt.shared.PaxTypes.{EeaMachineReadable, NonVisaNational}
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk}
import drt.shared.SplitRatiosNs.SplitRatios
import org.joda.time.format.DateTimeFormat
import org.specs2.mutable.Specification
import services.{CSVPassengerSplitsProvider, SDate}
import services.SDate.implicits._

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
