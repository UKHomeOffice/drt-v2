package passengersplits.csv

import drt.shared.ApiPaxTypeAndQueueCount
import drt.shared.PassengerSplits.VoyagePaxSplits
import drt.shared.PaxTypes.{EeaMachineReadable, NonVisaNational}
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk}
import drt.shared.SplitRatiosNs.SplitRatios
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.specs2.mutable.Specification
import services.SDate.implicits._
import services.{CSVPassengerSplitsProvider, SDate}

class EgatePercentagesFromPaxSplitsCsv extends Specification {

  val formatter: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd")

  "DRT-4568 Given a Flight Passenger Split" >> {
    "When we ask for the egate percentage, we get a multiplier" >> {
      val csvLines = Seq(s"""BA1234,JHB,100,0,0,0,${"70"},30,0,0,0,0,0,0,Monday,January,STN,T1,SA""")
      val csvSplitProvider = CSVPassengerSplitsProvider(csvLines)

      val split: Option[SplitRatios] = csvSplitProvider.getFlightSplitRatios("BA1234", "Monday", "January")
      val egatePct = CSVPassengerSplitsProvider.egatePercentageFromSplit(split, 0)
      val expectedEgatePercentage = 0.7d
      expectedEgatePercentage === egatePct
    }
    "We need the raw percentage at that level of the splits hierarchy - it should not be affected by higher levels " >> {
      val csvLines = Seq(s"""BA1234,JHB,50,50,0,0,${"70"},30,0,0,0,0,0,0,Monday,January,STN,T1,SA""")
      val csvSplitProvider = CSVPassengerSplitsProvider(csvLines)

      val split: Option[SplitRatios] = csvSplitProvider.getFlightSplitRatios("BA1234", "Monday", "January")
      val egatePct = CSVPassengerSplitsProvider.egatePercentageFromSplit(split, 0)
      val expectedEgatePercentage = 0.7d
      expectedEgatePercentage === egatePct
    }
    "Given the flight isn't in the CSV files" +
      "When we request the egatePercentage, we get the default percentage " >> {
      val csvLines = Seq(s"""BA1234,JHB,100,0,0,0,0,30,0,0,0,0,0,0,Monday,January,STN,T1,SA""")

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
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100, None),
        ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 20, None)
      ))
      val converted = vps.copy(paxSplits = List(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 30, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 70, None),
        ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 20, None)
      ))

      converted.paxSplits.toSet === CSVPassengerSplitsProvider.applyEgates(vps, 0.7).paxSplits.toSet

    }
    "Given an initial paxCount where the percentage would not be a round number, we put any remainder in the eeaDesk" +
      "so a 70% split on 99 people sends 30 to eeaDesk and 69 to eGates" >> {
      val vps = VoyagePaxSplits("STN", "BA", "978", 100, SDate(2017, 10, 1, 10, 30), List(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 99, None),
        ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 20, None)
      ))
      val converted = vps.copy(paxSplits = List(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 30, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 69, None),
        ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 20, None)
      ))

      converted.paxSplits.toSet === CSVPassengerSplitsProvider.applyEgates(vps, 0.7).paxSplits.toSet

    }
  }
}
