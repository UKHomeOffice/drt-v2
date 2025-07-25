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
import uk.gov.homeoffice.drt.db.serialisers.EgateEligibility
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

  "egateEligibleAndUnderAgePct" should {
    "return counts of all types of egate eligibles" in {
      val manifest = Seq(
        gbEgate, euEgate, b5jEgate,
        nonEea,
      )

      val (egateEligibleCount, egateUnderAgeCount) = EgateSimulations.egateEligibleAndUnderAgePct(B5JPlusTypeAllocator)(manifest)

      assert(egateEligibleCount == 75)
      assert(egateUnderAgeCount == 0)
    }

    "return counts of all types of underage egate" in {
      val manifest = Seq(
        gbBelowEgate, euBelowEgate, b5jBelowEgate,
        nonEea,
      )

      val (egateEligibleCount, egateUnderAgeCount) = EgateSimulations.egateEligibleAndUnderAgePct(B5JPlusTypeAllocator)(manifest)

      assert(egateEligibleCount == 0)
      assert(egateUnderAgeCount == 75)
    }
  }

  "netEgateEligiblePct" should {
    "return the egate eligibles as a percentage of the total passengers when there are no underage passengers" in {
      val egateEligiblePct = 5
      val egateUnderAgePct = 0
      val childParentRatio = 1.0
      val netEgateEligiblePercentage = EgateSimulations.netEgateEligiblePct(childParentRatio)(egateEligiblePct, egateUnderAgePct)
      assert(netEgateEligiblePercentage == 5.0)
    }

    "return the egate eligibles as a percentage of the total passengers when there are underage passengers" in {
      val egateEligiblePct = 5
      val egateUnderAgePct = 2
      val childParentRatio = 1.0
      val netEgateEligiblePercentage = EgateSimulations.netEgateEligiblePct(childParentRatio)(egateEligiblePct, egateUnderAgePct)
      assert(netEgateEligiblePercentage == 3.0) // 5 eligible, 2 underage, so 5 - (2 * 1) = 3 eligible for egates
    }
  }

  "bxUptakePct" should {
    "calculate the egate uptake given a range of eligible percentage and actual egate percentages" in {
      val uptakes = Map(
        (80, 80) -> 100d,
        (80, 60) -> 75d,
        (100, 50) -> 50d,
        (0, 0) -> 0d
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

  "drtTotalPaxEgateEligiblePctAndUnderAgePctForDate" should {
    "calculate the egate percentage as the average from the egate/desk splits from each flight" in {
      val terminal = T1
      val date = UtcDate(2025, 6, 19)
      val arrival = ArrivalGenerator.arrival(totalPax = Option(100), feedSource = LiveFeedSource)
      val manifest = VoyageManifestGenerator.voyageManifest(paxInfos = List.fill(100)(euPassport))

      val flightsWithManifests = (_: UtcDate, _: Terminal) => Future.successful(Seq.fill(2)((arrival, Some(manifest))))
      var counter = 0
      val egateAndDeskNos = IndexedSeq(
        (80d, 20d),
        (60d, 40d)
      )
      val egateAndDeskPaxForFlight = (_: Seq[ManifestPassengerProfile]) => {
        val r = egateAndDeskNos(counter)
        counter += 1
        r
      }
      val store = (_: UtcDate, _: Terminal, _: Int, _: Double, _: Double) => ()

      val function = EgateSimulations.drtTotalPaxEgateEligiblePctAndUnderAgePctForDate(flightsWithManifests, egateAndDeskPaxForFlight, store)
      val result = Await.result(function(date, terminal), 1.second)

      assert(result == (200, 70d, 30d))
    }
  }

  "bxTotalPaxAndEgatePctForDateAndTerminal" should {
    "calculate the egate percentage based on the total pax in the EGate queue" in {
      val terminal = T1
      val date = UtcDate(2025, 6, 19)
      val bxQueueTotalsForPortAndDate: (UtcDate, Terminal) => Future[Map[Queue, Int]] = (_, _) => Future.successful(Map(
        EGate -> 80,
        QueueDesk -> 20,
      ))

      val function = EgateSimulations.bxTotalPaxAndEgatePctForDateAndTerminal(bxQueueTotalsForPortAndDate)
      val result = Await.result(function(date, terminal), 1.second)

      assert(result == (100, 80d))
    }

    "return zero when there are no pax in any queue" in {
      val terminal = T1
      val date = UtcDate(2025, 6, 19)
      val bxQueueTotalsForPortAndDate: (UtcDate, Terminal) => Future[Map[Queue, Int]] = (_, _) => Future.successful(Map.empty)

      val function = EgateSimulations.bxTotalPaxAndEgatePctForDateAndTerminal(bxQueueTotalsForPortAndDate)
      val result = Await.result(function(date, terminal), 1.second)

      assert(result == (0, 0d))
    }
  }

  "drtTotalAndEgateEligibleAndActualPercentageForDateAndTerminal" should {
    "calculate the total pax, net egate eligible percentage and actual egate percentage" in {
      val terminal = T1
      val date = UtcDate(2025, 6, 19)

      val totalPax = 100
      val egateEligiblePct = 71d
      val underAgePct = 12d
      val adultToChildRatio = 1.2
      val uptakePct = 80d

      val cachedEgateEligibleAndUnderAgeForDate: (UtcDate, Terminal) => Future[Option[EgateEligibility]] = (_, _) => Future.successful(None)
      val drtTotalPaxEgateEligiblePctAndUnderAgePctForDate: (UtcDate, Terminal) => Future[(Int, Double, Double)] =
        (_, _) => Future.successful((totalPax, egateEligiblePct, underAgePct))
      val netEligiblePercentage = EgateSimulations.netEgateEligiblePct(adultToChildRatio) _

      val function = EgateSimulations.drtTotalAndEgateEligibleAndActualPercentageForDateAndTerminal(uptakePct)(
        cachedEgateEligibleAndUnderAgeForDate,
        drtTotalPaxEgateEligiblePctAndUnderAgePctForDate,
        netEligiblePercentage,
      )
      val result = Await.result(function(date, terminal), 1.second)

      val expectedNetEgateEligible = egateEligiblePct - (adultToChildRatio * underAgePct)
      val expectedActualEgatePct = expectedNetEgateEligible / 100d * uptakePct

      assert(result == (totalPax, expectedNetEgateEligible, expectedActualEgatePct))
    }
  }

  "bxAndDrtStatsForDate" should {
    "return 50% bx uptake for 30 bx egate pax out of 60 drt eligible" in {
      val terminal = T1
      val date = UtcDate(2025, 6, 19)

      val bxTotalPax = 80
      val bxEgatePct = 30d
      val drtTotalPax = 75
      val drtNetEgateEligiblePct = 60d
      val drtActualEgatePct = 10d

      val bxTotalPaxAndEgatePctForDateAndTerminal = (_: UtcDate, _: Terminal) => Future.successful((bxTotalPax, bxEgatePct))
      val drtTotalAndEgateEligibleAndActualPercentageForDateAndTerminal =
        (_: UtcDate, _: Terminal) => Future.successful((drtTotalPax, drtNetEgateEligiblePct, drtActualEgatePct))
      val bxUptakePct = EgateSimulations.bxUptakePct _

      val function = EgateSimulations.bxAndDrtStatsForDate(
        bxTotalPaxAndEgatePctForDateAndTerminal,
        drtTotalAndEgateEligibleAndActualPercentageForDateAndTerminal,
        bxUptakePct)
      val result = Await.result(function(date, terminal), 1.second)

      val expectedBxEgateUptakePct = bxEgatePct / drtNetEgateEligiblePct * 100d

      assert(result == (bxTotalPax, bxEgatePct, expectedBxEgateUptakePct, drtTotalPax, drtActualEgatePct))
    }
  }
}
