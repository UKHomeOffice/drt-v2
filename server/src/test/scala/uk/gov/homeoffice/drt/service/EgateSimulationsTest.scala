package uk.gov.homeoffice.drt.service

import controllers.ArrivalGenerator
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.wordspec.AnyWordSpec
import queueus.{PaxTypeQueueAllocation, TerminalQueueAllocator}
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import services.crunch.VoyageManifestGenerator
import services.crunch.VoyageManifestGenerator.{euPassport, visa}
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.models.CountryCodes.{Canada, France, UK}
import uk.gov.homeoffice.drt.models.DocumentType.Passport
import uk.gov.homeoffice.drt.models.{B5JPlusTypeAllocator, ManifestPassengerProfile}
import uk.gov.homeoffice.drt.ports.PaxTypes.{EeaMachineReadable, VisaNational}
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PaxAge, PaxType, PortCode}
import uk.gov.homeoffice.drt.time.UtcDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class EgateSimulationsTest extends AnyWordSpec {
  val egateUptake = 0.8
  val paxTypeAllocation: Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]] = Map(T1 -> Map(
    EeaMachineReadable -> Seq((EGate, egateUptake), (EeaDesk, 1 - egateUptake)),
    VisaNational -> Seq((NonEeaDesk, 1.0))
  ))
  val paxQueueAllocator: PaxTypeQueueAllocation = paxTypeQueueAllocator(hasTransfer = false, TerminalQueueAllocator(paxTypeAllocation))

  val gbEgate: ManifestPassengerProfile = ManifestPassengerProfile(Nationality(UK), Some(Passport), Some(PaxAge(20)), inTransit = false, None)
  val gbBelowEgate: ManifestPassengerProfile = ManifestPassengerProfile(Nationality(UK), Some(Passport), Some(PaxAge(1)), inTransit = false, None)
  val euEgate: ManifestPassengerProfile = ManifestPassengerProfile(Nationality(France), Some(Passport), Some(PaxAge(25)), inTransit = false, None)
  val euBelowEgate: ManifestPassengerProfile = ManifestPassengerProfile(Nationality(France), Some(Passport), Some(PaxAge(2)), inTransit = false, None)
  val b5jEgate: ManifestPassengerProfile = ManifestPassengerProfile(Nationality(Canada), Some(Passport), Some(PaxAge(50)), inTransit = false, None)
  val b5jBelowEgate: ManifestPassengerProfile = ManifestPassengerProfile(Nationality(Canada), Some(Passport), Some(PaxAge(5)), inTransit = false, None)
  val nonEea: ManifestPassengerProfile = ManifestPassengerProfile(Nationality("IND"), Some(Passport), Some(PaxAge(5)), inTransit = false, None)

  "totalPaxEgateEligibleAndUnderAge" should {
    "return counts of all types of egate eligibles" in {
      val manifest = Seq(
        gbEgate, euEgate, b5jEgate,
        nonEea,
      )

      val (egateEligibleCount, egateUnderAgeCount) = EgateSimulations.egateEligibleAndUnderAgePercentages(B5JPlusTypeAllocator)(manifest)

      assert(egateEligibleCount == 75)
      assert(egateUnderAgeCount == 0)
    }

    "return counts of all types of underage egate" in {
      val manifest = Seq(
        gbBelowEgate, euBelowEgate, b5jBelowEgate,
        nonEea,
      )

      val (egateEligibleCount, egateUnderAgeCount) = EgateSimulations.egateEligibleAndUnderAgePercentages(B5JPlusTypeAllocator)(manifest)

      assert(egateEligibleCount == 0)
      assert(egateUnderAgeCount == 75)
    }
  }

  "egateEligiblePercentage" should {
    "return the egate eligibles as a percentage of the total passengers when there are no underage passengers" in {
      val totalPax = 10
      val egateEligible = 5
      val egateUnderAge = 0
      val childParentRatio = 1.0
      val egateEligiblePercentage = EgateSimulations.netEgateEligiblePct(childParentRatio)(totalPax, egateEligible, egateUnderAge)
      assert(egateEligiblePercentage == 50.0)
    }

    "return the egate eligibles as a percentage of the total passengers when there are underage passengers" in {
      val totalPax = 10
      val egateEligible = 5
      val egateUnderAge = 2
      val childParentRatio = 1.0
      val egateEligiblePercentage = EgateSimulations.netEgateEligiblePct(childParentRatio)(totalPax, egateEligible, egateUnderAge)
      assert(egateEligiblePercentage == 30.0) // 5 eligible, 2 underage, so 5 - (2 * 1) = 3 eligible for egates
    }

    "return the egate eligibles as a percentage of the total passengers when there are underage passengers and multiple counts" in {
      val counts = Seq(
        (100, 20, 1), // 20 eligible, 1 underage
        (50, 10, 2), // 10 eligible, 2 underage
        (30, 5, 0), // 5 eligible, no underage
      )
      val childParentRatio = 1.5
      val egateEligiblePercentage = EgateSimulations.netEgateEligiblePct(childParentRatio)(counts.map(_._1).sum, counts.map(_._2).sum, counts.map(_._3).sum)
      assert(egateEligiblePercentage == (30d / 180) * 100) // 180 total, 35 eligible, 4.5 underage - leaves 30 eligible for egates
    }
  }

  "bxUptake" should {
    "calculate the egate uptake given a range of eligible percentage and actual egate percentages" in {
      val uptakes = Map(
        (80, 80) -> 1.0, // 80% of 100 is 80, which is the eligible percentage
        (80, 60) -> 0.75, // 60% of 80 is 48, which is less than the eligible percentage
        (100, 50) -> 0.5, // 50% of 100 is 50, which is less than the eligible percentage
        (0, 0) -> 0.0 // No eligible pax means no uptake
      )
      uptakes.foreach { case ((eligiblePercentage, egatePaxPercentage), expectedUptake) =>
        val uptake = EgateSimulations.bxUptakePct(eligiblePercentage, egatePaxPercentage)
        assert(uptake == expectedUptake, s"Expected $expectedUptake for eligible $eligiblePercentage and egate pax $egatePaxPercentage, got $uptake")
      }
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

      val arrivalsWithManifests = EgateSimulations.arrivalsWithManifestsForDateAndTerminal(
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

      val arrivalsWithManifests = EgateSimulations.arrivalsWithManifestsForDateAndTerminal(
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

      val arrivalsWithManifests = EgateSimulations.arrivalsWithManifestsForDateAndTerminal(
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

      val arrivalsWithManifests = EgateSimulations.arrivalsWithManifestsForDateAndTerminal(
        portCode,
        _ => Future.successful(None),
        _ => Future.successful(None),
        (_, _) => Future.successful(Seq(arrival)),
      )

      val result = Await.result(arrivalsWithManifests(UtcDate(2025, 6, 19), terminal), 1.second)

      assert(result == Seq((arrival, None)))
    }
  }
//
//  "drtEgatePercentageForDateAndTerminal" should {
//    "calculate the egate percentage as the average from the egate/desk splits from each flight" in {
//      val terminal = T1
//      val date = UtcDate(2025, 6, 19)
//      val arrival = ArrivalGenerator.arrival(totalPax = Option(100), feedSource = LiveFeedSource)
//      val manifest = VoyageManifestGenerator.voyageManifest(paxInfos = List.fill(100)(euPassport))
//
//      val flightsWithManifests = (_: UtcDate, _: Terminal) => Future.successful(Seq.fill(2)((arrival, Some(manifest))))
//      var counter = 0
//      val egateAndDeskNos = IndexedSeq(
//        (80, 20),
//        (60, 40)
//      )
//      val egateAndDeskPaxForFlight = (_: Arrival, _: Option[ManifestLike]) => {
//        val r = egateAndDeskNos(counter)
//        counter += 1
//        r
//      }
//
//      val function = EgateSimulations.drtEgatePercentageForDateAndTerminal(flightsWithManifests, egateAndDeskPaxForFlight)
//      val result = Await.result(function(date, terminal), 1.second)
//
//      assert(result == 70.0)
//    }
//  }
//
//  "bxEgatePercentageForDateAndTerminal" should {
//    "calculate the egate percentage based on the total pax in the EGate queue" in {
//      val terminal = T1
//      val date = UtcDate(2025, 6, 19)
//      val bxQueueTotalsForPortAndDate: (UtcDate, Terminal) => Future[Map[Queue, Int]] = (_, _) => Future.successful(Map(
//        EGate -> 80,
//        EeaDesk -> 20,
//        NonEeaDesk -> 0
//      ))
//
//      val function = EgateSimulations.bxEgatePercentageForDateAndTerminal(bxQueueTotalsForPortAndDate)
//      val result = Await.result(function(date, terminal), 1.second)
//
//      assert(result == 80.0)
//    }
//
//    "return zero when there are no pax in any queue" in {
//      val terminal = T1
//      val date = UtcDate(2025, 6, 19)
//      val bxQueueTotalsForPortAndDate: (UtcDate, Terminal) => Future[Map[Queue, Int]] = (_, _) => Future.successful(Map.empty)
//
//      val function = EgateSimulations.bxEgatePercentageForDateAndTerminal(bxQueueTotalsForPortAndDate)
//      val result = Await.result(function(date, terminal), 1.second)
//
//      assert(result == 0.0)
//    }
//  }
//
//  "bxAndDrtEgatePercentageForDate" should {
//    "return a tuple of egate percentages from both BX and DRT calculations" in {
//      val terminal = T1
//      val date = UtcDate(2025, 6, 19)
//
//      val bxEgatePercentageForDateAndTerminal = (_: UtcDate, _: Terminal) => Future.successful(90.0)
//      val drtEgatePercentageForDateAndTerminal = (_: UtcDate, _: Terminal) => Future.successful(70.0)
//
//      val function = EgateSimulations.bxAndDrtEgatePercentageForDate(bxEgatePercentageForDateAndTerminal, drtEgatePercentageForDateAndTerminal)
//      val result = Await.result(function(date, terminal), 1.second)
//
//      assert(result == (90.0, 70.0))
//    }
//  }
}
