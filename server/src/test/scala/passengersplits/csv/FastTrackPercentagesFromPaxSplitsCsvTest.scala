package passengersplits.csv

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.testkit.TestKit
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import drt.shared.PassengerSplits.{FlightNotFound, SplitsPaxTypeAndQueueCount, VoyagePaxSplits}
import drt.shared.PaxTypes.{EeaMachineReadable, NonVisaNational, VisaNational}
import drt.shared.Queues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared.{Arrival, PaxTypeAndQueue, Queues, SDateLike}
import org.joda.time.format.DateTimeFormat
import org.specs2.mutable.{Specification, SpecificationLike}
import passengersplits.csv.SplitsMocks.{MockSplitsActor, testVoyagePaxSplits}
import .splitRatioProviderWithCsvPercentages
import services.SDate.implicits._
import services.{CSVPassengerSplitsProvider, FastTrackPercentages, SDate}
import controllers.ArrivalGenerator.apiFlight
import org.joda.time.DateTimeZone

import scala.concurrent.duration._

class FastTrackPercentagesFromPaxSplitsCsvTest extends Specification {

  val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")

  "DRT-4537 Given a Flight Passenger Split" >> {
    "When we ask for the fast track percentages, we get a multiplier for visa and non visa nationals" >> {
      val visaToFastTrack = "60"
      val nonVisaToFastTrack = "70"
      val csvLines = Seq(s"BA1234,JHB,0,0,40,60,0,0,0,$nonVisaToFastTrack,30,$visaToFastTrack,40,0,Monday,January,STN,T1,SA")
      val csvSplitProvider = CSVPassengerSplitsProvider(csvLines)

      val split: Option[SplitRatios] = csvSplitProvider.getFlightSplitRatios("BA1234", "Monday", "January")
//      println(s"a) splitRatios sum ${split.map(_.splits.map(_.ratio).sum)}")
      val fastTrackPercentages = CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(split, 0, 0)
      val expectedFastTrackPercentage = FastTrackPercentages(nonVisaNational = 0.7, visaNational = 0.6)

      fastTrackPercentages === expectedFastTrackPercentage
    }
    "Given csvSplit line where the fasttrackpct are 14% and 8%" >> {
      "When we ask for the fast track percentages, we get a multiplier for visa and non visa nationals" >> {
        val nonVisaToFastTrack = "8"
        val visaToFastTrack = "14"
        val csvLines = Seq(s"""BA1234,JHB,0,0,50,50,0,0,0,$nonVisaToFastTrack,92,$visaToFastTrack,86,0,Monday,January,STN,T1,SA""")
        val csvSplitProvider = CSVPassengerSplitsProvider(csvLines)

        val split: Option[SplitRatios] = csvSplitProvider.getFlightSplitRatios("BA1234", "Monday", "January")
//        println(s"b) splitRatios sum ${split.map(_.splits.map(_.ratio).sum)}")

        val fastTrackPercentages = CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(split, 0, 0)
        val expectedFastTrackPercentage = FastTrackPercentages(nonVisaNational = 0.08, visaNational = 0.14)
        fastTrackPercentages === expectedFastTrackPercentage
      }
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
    "If fastTrack percentage is 0 then don't include a SplitsPaxTypeAndQueueCount because it's probably not in AirportConfig and could break other things" >> {
      val vps = VoyagePaxSplits("STN", "BA", "978", 100, SDate(2017, 10, 1, 10, 30), List(
        SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 100)
      ))
      val converted = vps.copy(paxSplits = List(
        SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 100)
      ))

      converted.paxSplits.toSet === CSVPassengerSplitsProvider.applyFastTrack(vps, FastTrackPercentages(0, 0)).paxSplits.toSet
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
        SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 1),
        SplitsPaxTypeAndQueueCount(NonVisaNational, FastTrack, 98)
      ))

      converted.paxSplits.toSet === CSVPassengerSplitsProvider.applyFastTrack(vps, FastTrackPercentages(0, 0.99)).paxSplits.toSet
    }
  }
}

class FastTrackAndEgateCompositionTests extends TestKit(ActorSystem("WorkloadwithAdvPaxInfoSplits", ConfigFactory.empty()))
  with SpecificationLike {

  isolated

  implicit val timeout: Timeout = 1 second

  import scala.concurrent.ExecutionContext.Implicits.global

  "DRT-4567, DRT-4568 We should apply both fast track and E-Gate splits to API VoyagePaxSplits" >> {
    "Given only CSV EGate percentages" +
      " when we call the splitRatioProvider, we get egate splits for EeaMachineReadable" >> {
      val scheduledArrivalTimeStr = "2017-06-04T16:15:00"

      val passengerInfoRouterActor: AskableActorRef = MockSplitsActor(SplitsMocks.defaultSplits(SDate(scheduledArrivalTimeStr, DateTimeZone.UTC)))
      val egatePercentageProvider = (flight: Arrival) => 0.9d
      val fastTrackPercentageProvider = (flight: Arrival) => None

      val provider = splitRatioProviderWithCsvPercentages("LHR")(passengerInfoRouterActor)(egatePercentageProvider, fastTrackPercentageProvider) _

      val testFlight = apiFlight(flightId = 1, iata = "BA1234", schDt = scheduledArrivalTimeStr).copy(ActPax = 100)

      val actual = provider(testFlight)
      val actualEeaMachine = actual.get.splits.filter(_.paxType.passengerType == EeaMachineReadable)
      val expected = List(
        SplitRatio(PaxTypeAndQueue(EeaMachineReadable, Queues.EGate), 0.45),
        SplitRatio(PaxTypeAndQueue(EeaMachineReadable, Queues.EeaDesk), 0.05),
        SplitRatio(PaxTypeAndQueue(EeaMachineReadable, Queues.EGate), 0.5))
      actualEeaMachine === expected
    }

    "Given only CSV fasttrack percentages provider\n" >> {
      " AND dqAPI splits for VisaNationals" +
        " when we call the splitRatioProvider, we get fasttrack splits on top" >> {
        val scheduledArrivalTimeStr = "2017-06-04T16:15:00"
        val scheduledArrivalDateTime = SDate(scheduledArrivalTimeStr, DateTimeZone.UTC)

        val splitsFromDqApi = testVoyagePaxSplits(scheduledArrivalDateTime, List(
          SplitsPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 100)
          //        SplitsPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 50)
        ))

        val passengerInfoRouterActor: AskableActorRef = MockSplitsActor(splitsFromDqApi)
        val egatePercentageProvider = (flight: Arrival) => 0d

        val fastTrackPercentageProvider = (flight: Arrival) => Some(FastTrackPercentages(
          visaNational = 0.03,
          nonVisaNational = 0.06))
        val provider = splitRatioProviderWithCsvPercentages("LHR")(passengerInfoRouterActor)(egatePercentageProvider, fastTrackPercentageProvider) _

        val testFlight = apiFlight(flightId = 1, iata = "BA1234", schDt = scheduledArrivalTimeStr).copy(ActPax = 100)


        val result = provider(testFlight)
        val visaRelated = List(NonVisaNational, VisaNational)
        val actualFastTrack = result.get.splits.filter(visaRelated contains _.paxType.passengerType).toSet


        val expected = Set(
          SplitRatio(PaxTypeAndQueue(VisaNational, Queues.NonEeaDesk), 0.97),
          SplitRatio(PaxTypeAndQueue(VisaNational, Queues.FastTrack), 0.03))

        actualFastTrack === expected
      }
      " AND dqAPI splits for NonVisaNationals" +
        " when we call the splitRatioProvider, we get fasttrack splits on top" >> {
        val scheduledArrivalTimeStr = "2017-06-04T16:15:00"
        val scheduledArrivalDateTime = SDate(scheduledArrivalTimeStr, DateTimeZone.UTC)

        val splitsFromDqApi = testVoyagePaxSplits(scheduledArrivalDateTime, List(
          //          SplitsPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 100)
          SplitsPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 100)
        ))

        val passengerInfoRouterActor: AskableActorRef = MockSplitsActor(splitsFromDqApi)
        val egatePercentageProvider = (flight: Arrival) => 0d

        val fastTrackPercentageProvider = (flight: Arrival) => Some(FastTrackPercentages(
          visaNational = 0.03,
          nonVisaNational = 0.06))
        val provider = splitRatioProviderWithCsvPercentages("LHR")(passengerInfoRouterActor)(egatePercentageProvider, fastTrackPercentageProvider) _

        val testFlight = apiFlight(flightId = 1, iata = "BA1234", schDt = scheduledArrivalTimeStr).copy(ActPax = 100)


        val result = provider(testFlight)
        val visaRelated = List(NonVisaNational, VisaNational)
        val actualFastTrack = result.get.splits.filter(visaRelated contains _.paxType.passengerType).toSet


        val expected = Set(
          SplitRatio(PaxTypeAndQueue(NonVisaNational, Queues.NonEeaDesk), 0.94),
          SplitRatio(PaxTypeAndQueue(NonVisaNational, Queues.FastTrack), 0.06))

        actualFastTrack === expected
      }
      " AND dqAPI splits for NonVisaNationals and NonVisaNationals" +
        " when we call the splitRatioProvider, we get fasttrack splits on top" >> {
        val scheduledArrivalTimeStr = "2017-06-04T16:15:00"
        val scheduledArrivalDateTime = SDate(scheduledArrivalTimeStr, DateTimeZone.UTC)

        val splitsFromDqApi = testVoyagePaxSplits(scheduledArrivalDateTime, List(
          SplitsPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 100),
          SplitsPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 100)
        ))

        val passengerInfoRouterActor: AskableActorRef = MockSplitsActor(splitsFromDqApi)
        val egatePercentageProvider = (flight: Arrival) => 0d

        val fastTrackPercentageProvider = (flight: Arrival) => Some(FastTrackPercentages(
          visaNational = 0.03,
          nonVisaNational = 0.06))
        val provider = splitRatioProviderWithCsvPercentages("LHR")(passengerInfoRouterActor)(egatePercentageProvider, fastTrackPercentageProvider) _

        val testFlight = apiFlight(flightId = 1, iata = "BA1234", schDt = scheduledArrivalTimeStr).copy(ActPax = 100)

        val result = provider(testFlight)
        val visaRelated = List(NonVisaNational, VisaNational)
        val actualFastTrack = result.get.splits.filter(visaRelated contains _.paxType.passengerType).toSet

        //we divide by two below, because the splitratio is a relationship of the SplitsPaxTypeAndQueueCount.paxCount : ActPax
        val expected = Set(
          SplitRatio(PaxTypeAndQueue(VisaNational, Queues.NonEeaDesk), 0.97 / 2),
          SplitRatio(PaxTypeAndQueue(VisaNational, Queues.FastTrack), 0.03 / 2),
          SplitRatio(PaxTypeAndQueue(NonVisaNational, Queues.NonEeaDesk), 0.94 / 2),
          SplitRatio(PaxTypeAndQueue(NonVisaNational, Queues.FastTrack), 0.06 / 2))

        actualFastTrack === expected
      }
    }

  }
}

object SplitsMocks {

  class MockSplitsActor(splits: VoyagePaxSplits) extends Actor {
    /* This is a mock of the SingleFlightActor - which is a FlightSplit calculator - returning splits based on AdvPaxInfo
    * that => on the splits declaration is probably not safe usually, but in the single threaded test world, I'm prepared to try it.
    *
    * */
    def receive: Receive = {
      case ReportVoyagePaxSplit(dp, carrierCode, voyageNumber, scheduledArrivalDateTime) =>
        sender ! splits
    }
  }

  object MockSplitsActor {
    def apply[T](splits: T)(implicit system: ActorSystem) = system.actorOf(Props(classOf[SplitsMocks.MockSplitsActor], splits))
  }

  class NotFoundSplitsActor extends Actor {
    def receive: Receive = {
      case ReportVoyagePaxSplit(dp, carrierCode, voyageNumber, scheduledArrivalDateTime) =>
        sender ! FlightNotFound(carrierCode, voyageNumber, scheduledArrivalDateTime)
    }
  }

  def defaultSplits(scheduledArrivalDateTime: SDateLike): VoyagePaxSplits = testVoyagePaxSplits(scheduledArrivalDateTime, List(
    SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 10),
    SplitsPaxTypeAndQueueCount(EeaMachineReadable, EGate, 10)
  ))

  def testVoyagePaxSplits(scheduledArrivalDateTime: SDateLike, passengerNumbers: List[SplitsPaxTypeAndQueueCount]): VoyagePaxSplits = {
    val splits = VoyagePaxSplits("LGW", "BA", "0001", passengerNumbers.map(_.paxCount).sum, scheduledArrivalDateTime, passengerNumbers)
    splits
  }
}
