package passengersplits.csv

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.testkit.TestKit
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import drt.shared.{ApiFlight, PaxTypeAndQueue, Queues, SDateLike}
import drt.shared.PassengerSplits.{FlightNotFound, SplitsPaxTypeAndQueueCount, VoyagePaxSplits}
import drt.shared.PaxTypes.{EeaMachineReadable, NonVisaNational, VisaNational}
import drt.shared.Queues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import org.joda.time.format.DateTimeFormat
import org.specs2.mutable.{Specification, SpecificationLike}
import passengersplits.core.PassengerInfoRouterActor.ReportVoyagePaxSplit
import services.AdvPaxSplitsProvider.splitRatioProviderWithCsvPercentages
import services.SDate.implicits._
import services.{CSVPassengerSplitsProvider, FastTrackPercentages, SDate, WorkloadCalculatorTests}

import scala.concurrent.duration._

class FastTrackPercentagesFromPaxSplitsCsv extends Specification {

  val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")

  "DRT-4537 Given a Flight Passenger Split" >> {
    "When we ask for the fast track percentages, we get a multiplier for visa and non visa nationals" >> {
      val csvLines = Seq(s"""BA1234,JHB,0,0,40,60,0,0,0,100,0,100,0,0,Monday,January,STN,T1,SA""")
      val csvSplitProvider = CSVPassengerSplitsProvider(csvLines)

      val split: Option[SplitRatios] = csvSplitProvider.getFlightSplitRatios("BA1234", "Monday", "January")
      val fastTrackPercentages = CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(split, 0, 0)
      val expectedFastTrackPercentage = FastTrackPercentages(visaNational = 0.6, nonVisaNational = 0.4)

      fastTrackPercentages === expectedFastTrackPercentage
    }
    "When we ask for the fast track percentages, we get a multiplier for visa and non visa nationals" >> {
      val csvLines = Seq(s"""BA1234,JHB,0,0,50,50,0,0,0,14,0,8,0,0,Monday,January,STN,T1,SA""")
      val csvSplitProvider = CSVPassengerSplitsProvider(csvLines)

      val split: Option[SplitRatios] = csvSplitProvider.getFlightSplitRatios("BA1234", "Monday", "January")
      val fastTrackPercentages = CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(split, 0, 0)
      val expectedFastTrackPercentage = FastTrackPercentages(visaNational = 0.04, nonVisaNational = 0.07)
      fastTrackPercentages === expectedFastTrackPercentage
    }

    "Given the flight isn't in the CSV files" +
      "When we request the fast track percentage, we get the default percentage " >> {
      val csvLines = Seq(s"""BA1234,JHB,0,0,50,50,0,0,0,14,0,8,0,0,Monday,January,STN,T1,SA""")

      val csvSplitProvider = CSVPassengerSplitsProvider(csvLines)

      val split: Option[SplitRatios] = csvSplitProvider.getFlightSplitRatios("EZ199", "Monday", "January")
      val defaultVisaPct = 0.04d
      val defaultNonVisaPct = 0.07d
      val fastTrackPercentages = CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(split, defaultVisaPct, defaultNonVisaPct)

      val expectedFastTrackPercentage = FastTrackPercentages(visaNational = 0.04, nonVisaNational = 0.07)
      fastTrackPercentages === expectedFastTrackPercentage
    }

    "We can convert a VoyagePassengerInfo (split from DQ API) into an ApiSplit where we apply a percentage calculation to the" +
      "visa nationals, diverting that percentage to fast track" >> {
      val vps = VoyagePaxSplits("STN", "BA", "978", 100, SDate(2017, 10, 1, 10, 30), List(
        SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 100)
      ))
      val converted = vps.copy(paxSplits = List(
        SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 60),
        SplitsPaxTypeAndQueueCount(NonVisaNational, FastTrack, 40)
      ))

      converted.paxSplits.toSet === CSVPassengerSplitsProvider.applyFastTrack(vps, FastTrackPercentages(0, 0.4)).paxSplits.toSet
    }

    "We can convert a VoyagePassengerInfo (split from DQ API) into an ApiSplit where we apply a percentage calculation to the" +
      "visa nationals, diverting that percentage to fast track" >> {
      val vps = VoyagePaxSplits("STN", "BA", "978", 100, SDate(2017, 10, 1, 10, 30), List(
        SplitsPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 100)
      ))
      val converted = vps.copy(paxSplits = List(
        SplitsPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 60),
        SplitsPaxTypeAndQueueCount(VisaNational, FastTrack, 40)
      ))

      converted.paxSplits.toSet === CSVPassengerSplitsProvider.applyFastTrack(vps, FastTrackPercentages(0.4, 0)).paxSplits.toSet
    }

    "Given an initial paxCount where the percentage would not be a round number, we put any remainder in the nonEeaDesk" +
      "so a 70% split on 99 people sends 30 to nonEeaDesk and 69 to fastTrack" >> {
      val vps = VoyagePaxSplits("STN", "BA", "978", 100, SDate(2017, 10, 1, 10, 30), List(
        SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 99)
      ))
      val converted = vps.copy(paxSplits = List(
        SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk,1),
        SplitsPaxTypeAndQueueCount(NonVisaNational, FastTrack, 98)
      ))

      converted.paxSplits.toSet === CSVPassengerSplitsProvider.applyFastTrack(vps, FastTrackPercentages(0,0.99)).paxSplits.toSet
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

class FastTrackAndEgateCompositionTests extends  TestKit(ActorSystem("WorkloadwithAdvPaxInfoSplits", ConfigFactory.empty()))
with SpecificationLike {

  isolated

  implicit val timeout: Timeout = 3 seconds


  import scala.concurrent.ExecutionContext.Implicits.global

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

  "DRT-4567, DRT-4568 We should apply both fast track and E-Gate splits to API VoyagePaxSplits" >> {


    "Given CSV EGate percentages" >> {
      val passengerInfoRouterActor: AskableActorRef = system.actorOf(Props(classOf[SplitsMocks.MockSplitsActor]))
      val egatePercentageProvider = (flight: ApiFlight) => 0.9d


      val provider = splitRatioProviderWithCsvPercentages("LHR")(passengerInfoRouterActor)(egatePercentageProvider) _

      val result = provider(apiFlight("BA1234", "2017-06-04T16:15:00").copy(ActPax = 100))
      val expected = Some(
        SplitRatios(
          List(
            SplitRatio(PaxTypeAndQueue(EeaMachineReadable,Queues.EGate),0.45),
            SplitRatio(PaxTypeAndQueue(EeaMachineReadable,Queues.EeaDesk),0.05),
            SplitRatio(PaxTypeAndQueue(EeaMachineReadable,Queues.EGate),0.5)), "AdvancedPaxInfo")
      )

      result === expected
    }

  }
}

object SplitsMocks {

  class MockSplitsActor extends Actor {
    def receive: Receive = {
      case ReportVoyagePaxSplit(dp, carrierCode, voyageNumber, scheduledArrivalDateTime) =>
        val splits: VoyagePaxSplits = testVoyagePaxSplits(scheduledArrivalDateTime, List(
          SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 10),
          SplitsPaxTypeAndQueueCount(EeaMachineReadable, EGate, 10)
        ))
        sender ! splits
    }
  }

  class NotFoundSplitsActor extends Actor {
    def receive: Receive = {
      case ReportVoyagePaxSplit(dp, carrierCode, voyageNumber, scheduledArrivalDateTime) =>
        sender ! FlightNotFound(carrierCode, voyageNumber, scheduledArrivalDateTime)
    }
  }

  def testVoyagePaxSplits(scheduledArrivalDateTime: SDateLike, passengerNumbers: List[SplitsPaxTypeAndQueueCount]) = {
    val splits = VoyagePaxSplits("LGW", "BA", "0001", passengerNumbers.map(_.paxCount).sum, scheduledArrivalDateTime, passengerNumbers)
    splits
  }
}
