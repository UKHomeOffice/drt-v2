package uk.gov.homeoffice.drt.service

import controllers.ArrivalGenerator
import manifests.queues.SplitsCalculator
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.wordspec.AnyWordSpec
import queueus.TerminalQueueAllocator
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import services.crunch.VoyageManifestGenerator.{euPassport, visa}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.models.ManifestLike
import uk.gov.homeoffice.drt.ports.PaxTypes.{EeaMachineReadable, VisaNational}
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PaxType, PaxTypeAndQueue, PortCode}
import uk.gov.homeoffice.drt.testsystem.feeds.test.VoyageManifestGenerator
import uk.gov.homeoffice.drt.time.UtcDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class EgateUptakeSimulationTest extends AnyWordSpec {
  val egateUptake = 0.8
  val paxTypeAllocation: Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]] = Map(T1 -> Map(
    EeaMachineReadable -> Seq((EGate, egateUptake), (EeaDesk, 1 - egateUptake)),
    VisaNational -> Seq((NonEeaDesk, 1.0))
  ))
  val paxQueueAllocator = paxTypeQueueAllocator(hasTransfer = false, TerminalQueueAllocator(paxTypeAllocation))

  "queueAllocationForEgateUptake" should {
    "update pax types with an egate/desk split based on given egate uptake, and leave others alone" in {
      val allocation: Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]] = Map(T1 -> Map(
        EeaMachineReadable -> Seq((EGate, 0.5), (EeaDesk, 0.5)),
        VisaNational -> Seq((NonEeaDesk, 1))),
      )
      val updatedUptake = 0.8
      val updated = EgateUptakeSimulation.queueAllocationForEgateUptake(allocation, updatedUptake)
      assert(updated == Map(T1 -> Map(
        EeaMachineReadable -> Seq((EGate, updatedUptake), (EeaDesk, 1 - updatedUptake)),
        VisaNational -> Seq((NonEeaDesk, 1))
      )))
    }
  }

  "egateAndDeskPaxForFlight" should {
    val fallbacks = QueueFallbacks((_, _) => Seq.empty)

    "give total egate and desk pax that match the egate uptake in the splits calculator and all manifest pax are egate eligible" in {
      val splitsCalculator = SplitsCalculator(paxQueueAllocator, Map.empty[Terminal, SplitRatios])
      val arrival = ArrivalGenerator.arrival(totalPax = Option(100), feedSource = LiveFeedSource)
      val manifest = VoyageManifestGenerator.voyageManifest(paxInfos = List.fill(100)(euPassport))

      val fallbacks = QueueFallbacks((_, _) => Seq.empty)
      val (egatePax, deskPax) = EgateUptakeSimulation.egateAndDeskPaxForFlight(splitsCalculator, fallbacks)(arrival, Option(manifest))

      assert(egatePax == 80)
      assert(deskPax == 20)
    }

    "give zero egate and 100% pax when there are no egate eligible pax in the manifest" in {
      val splitsCalculator = SplitsCalculator(paxQueueAllocator, Map.empty[Terminal, SplitRatios])
      val arrival = ArrivalGenerator.arrival(totalPax = Option(100), feedSource = LiveFeedSource)
      val manifest = VoyageManifestGenerator.voyageManifest(paxInfos = List.fill(100)(visa))

      val (egatePax, deskPax) = EgateUptakeSimulation.egateAndDeskPaxForFlight(splitsCalculator, fallbacks)(arrival, Option(manifest))

      assert(egatePax == 0)
      assert(deskPax == 100)
    }

    "give total egate and desk pax that match the terminal splits when there's no manifest" in {
      val terminalSplits: Map[Terminal, SplitRatios] = Map(T1 -> SplitRatios(SplitSources.TerminalAverage, List(
        SplitRatio(PaxTypeAndQueue(EeaMachineReadable, EeaDesk), 0.2),
        SplitRatio(PaxTypeAndQueue(EeaMachineReadable, EGate), 0.8),
      )))
      val splitsCalculator = SplitsCalculator(paxQueueAllocator, terminalSplits)
      val arrival = ArrivalGenerator.arrival(totalPax = Option(100), feedSource = LiveFeedSource)

      val (egatePax, deskPax) = EgateUptakeSimulation.egateAndDeskPaxForFlight(splitsCalculator, fallbacks)(arrival, None)

      assert(egatePax == 80)
      assert(deskPax == 20)
    }
  }

  "arrivalsWithManifestsForDateAndTerminal" should {
    implicit val system: ActorSystem = ActorSystem("EgateUptakeSimulationTest")
    implicit val mat: Materializer = Materializer.matFromSystem

    "return arrivals with live manifests when available" in {
      val terminal = T1
      val portCode = PortCode("STN")
      val arrival = ArrivalGenerator.arrival(totalPax = Option(100), feedSource = LiveFeedSource)
      val liveManifest = VoyageManifestGenerator.voyageManifest(paxInfos = List.fill(100)(visa))
      val historicManifest = VoyageManifestGenerator.voyageManifest(paxInfos = List.fill(100)(euPassport))

      val arrivalsWithManifests = EgateUptakeSimulation.arrivalsWithManifestsForDateAndTerminal(
        portCode,
        _ => Future.successful(Some(liveManifest)),
        _ => Future.successful(Some(historicManifest)),
        (_, _) => Future.successful(Seq(arrival)),
      )

      val result = Await.result(arrivalsWithManifests(UtcDate(2025, 6, 19), terminal), 1.second)

      assert(result == Seq((arrival, Some(liveManifest))))
    }

    "return arrivals with historic manifests when live manifests are not available" in {
      val terminal = T1
      val portCode = PortCode("STN")
      val arrival = ArrivalGenerator.arrival(totalPax = Option(100), feedSource = LiveFeedSource)
      val historicManifest = VoyageManifestGenerator.voyageManifest(paxInfos = List.fill(100)(euPassport))

      val arrivalsWithManifests = EgateUptakeSimulation.arrivalsWithManifestsForDateAndTerminal(
        portCode,
        _ => Future.successful(None),
        _ => Future.successful(Some(historicManifest)),
        (_, _) => Future.successful(Seq(arrival)),
      )

      val result = Await.result(arrivalsWithManifests(UtcDate(2025, 6, 19), terminal), 1.second)

      assert(result == Seq((arrival, Some(historicManifest))))
    }

    "return empty when no arrivals are found for the date and terminal" in {
      val terminal = T1
      val portCode = PortCode("STN")

      val arrivalsWithManifests = EgateUptakeSimulation.arrivalsWithManifestsForDateAndTerminal(
        portCode,
        _ => Future.successful(None),
        _ => Future.successful(None),
        (_, _) => Future.successful(Seq.empty),
      )

      val result = Await.result(arrivalsWithManifests(UtcDate(2025, 6, 19), terminal), 1.second)

      assert(result.isEmpty)
    }

    "return empty when both live and historic manifests are not available" in {
      val terminal = T1
      val portCode = PortCode("STN")
      val arrival = ArrivalGenerator.arrival(totalPax = Option(100), feedSource = LiveFeedSource)

      val arrivalsWithManifests = EgateUptakeSimulation.arrivalsWithManifestsForDateAndTerminal(
        portCode,
        _ => Future.successful(None),
        _ => Future.successful(None),
        (_, _) => Future.successful(Seq(arrival)),
      )

      val result = Await.result(arrivalsWithManifests(UtcDate(2025, 6, 19), terminal), 1.second)

      assert(result == Seq((arrival, None)))
    }
  }

  "drtEgatePercentageForDateAndTerminal" should {
    "calculate the egate percentage as the average from the egate/desk splits from each flight" in {
      val terminal = T1
      val date = UtcDate(2025, 6, 19)
      val arrival = ArrivalGenerator.arrival(totalPax = Option(100), feedSource = LiveFeedSource)
      val manifest = VoyageManifestGenerator.voyageManifest(paxInfos = List.fill(100)(euPassport))

      val flightsWithManifests = (_: UtcDate, _: Terminal) => Future.successful(Seq.fill(2)((arrival, Some(manifest))))
      var counter = 0
      val egateAndDeskNos = IndexedSeq(
        (80, 20),
        (60, 40)
      )
      val egateAndDeskPaxForFlight = (_: Arrival, _: Option[ManifestLike]) => {
        val r = egateAndDeskNos(counter)
        counter += 1
        r
      }

      val function = EgateUptakeSimulation.drtEgatePercentageForDateAndTerminal(flightsWithManifests, egateAndDeskPaxForFlight)
      val result = Await.result(function(date, terminal), 1.second)

      assert(result == 70.0)
    }
  }

  "bxEgatePercentageForDateAndTerminal" should {
    "calculate the egate percentage based on the total pax in the EGate queue" in {
      val terminal = T1
      val date = UtcDate(2025, 6, 19)
      val bxQueueTotalsForPortAndDate: (UtcDate, Terminal) => Future[Map[Queue, Int]] = (_, _) => Future.successful(Map(
        EGate -> 80,
        EeaDesk -> 20,
        NonEeaDesk -> 0
      ))

      val function = EgateUptakeSimulation.bxEgatePercentageForDateAndTerminal(bxQueueTotalsForPortAndDate)
      val result = Await.result(function(date, terminal), 1.second)

      assert(result == 80.0)
    }

    "return zero when there are no pax in any queue" in {
      val terminal = T1
      val date = UtcDate(2025, 6, 19)
      val bxQueueTotalsForPortAndDate: (UtcDate, Terminal) => Future[Map[Queue, Int]] = (_, _) => Future.successful(Map.empty)

      val function = EgateUptakeSimulation.bxEgatePercentageForDateAndTerminal(bxQueueTotalsForPortAndDate)
      val result = Await.result(function(date, terminal), 1.second)

      assert(result == 0.0)
    }
  }

  "bxAndDrtEgatePercentageForDate" should {
    "return a tuple of egate percentages from both BX and DRT calculations" in {
      val terminal = T1
      val date = UtcDate(2025, 6, 19)

      val bxEgatePercentageForDateAndTerminal = (_: UtcDate, _: Terminal) => Future.successful(90.0)
      val drtEgatePercentageForDateAndTerminal = (_: UtcDate, _: Terminal) => Future.successful(70.0)

      val function = EgateUptakeSimulation.bxAndDrtEgatePercentageForDate(bxEgatePercentageForDateAndTerminal, drtEgatePercentageForDateAndTerminal)
      val result = Await.result(function(date, terminal), 1.second)

      assert(result == (90.0, 70.0))
    }
  }
}
