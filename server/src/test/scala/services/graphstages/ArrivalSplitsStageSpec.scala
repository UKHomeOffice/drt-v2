package services.graphstages

import actors.acking.AckingReceiver.StreamCompleted
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.Queues.EGate
import drt.shared.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared.Terminals.{T1, T2, T3}
import drt.shared._
import drt.shared.api.Arrival
import manifests.passengers.BestAvailableManifest
import manifests.queues.SplitsCalculator
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser.{ManifestDateOfArrival, ManifestTimeOfArrival, VoyageManifest}
import queueus.{B5JPlusWithTransitTypeAllocator, PaxTypeQueueAllocation, TerminalQueueAllocatorWithFastTrack}
import services.SDate
import services.crunch.{CrunchTestLike, PassengerInfoGenerator, TestConfig}

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._


object TestableArrivalSplits {
  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def groupByCodeShares(flights: Seq[ApiFlightWithSplits]): Seq[(ApiFlightWithSplits, Set[Arrival])] = flights.map(f => (f, Set(f.apiFlight)))

  def apply(splitsCalculator: SplitsCalculator, testProbe: TestProbe, now: () => SDateLike): RunnableGraph[(SourceQueueWithComplete[ArrivalsDiff], SourceQueueWithComplete[List[BestAvailableManifest]], SourceQueueWithComplete[List[BestAvailableManifest]])] = {
    val arrivalSplitsStage = new ArrivalSplitsGraphStage(
      name = "",
      optionalInitialFlights = None,
      refreshManifestsOnStart = false,
      splitsCalculator = splitsCalculator,
      expireAfterMillis = oneDayMillis,
      now = now
    )

    val arrivalsDiffSource = Source.queue[ArrivalsDiff](1, OverflowStrategy.backpressure)
    val manifestsLiveSource = Source.queue[List[BestAvailableManifest]](1, OverflowStrategy.backpressure)
    val manifestsHistoricSource = Source.queue[List[BestAvailableManifest]](1, OverflowStrategy.backpressure)

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(
      arrivalsDiffSource.async,
      manifestsLiveSource.async,
      manifestsHistoricSource.async
    )((_, _, _)) {

      implicit builder =>
        (
          arrivalsDiff,
          manifestsLive,
          manifestsHistoric
        ) =>
          val arrivalSplitsStageAsync = builder.add(arrivalSplitsStage.async)
          val sink = builder.add(Sink.actorRef(testProbe.ref, StreamCompleted))

          arrivalsDiff.out ~> arrivalSplitsStageAsync.in0
          manifestsLive.out ~> arrivalSplitsStageAsync.in1
          manifestsHistoric.out ~> arrivalSplitsStageAsync.in2

          arrivalSplitsStageAsync.out ~> sink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}

class ArrivalSplitsStageSpec extends CrunchTestLike {
  val splitsProvider: (String, MilliDate) => Option[SplitRatios] = (_, _) => {
    val eeaMrToDeskSplit = SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk), 0.5)
    val eeaNmrToDeskSplit = SplitRatio(PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk), 0.5)
    Option(SplitRatios(List(eeaMrToDeskSplit, eeaNmrToDeskSplit), SplitSources.Historical))
  }

  "Given an arrival splits stage " >> {
    val paxTypeQueueAllocation = PaxTypeQueueAllocation(
      B5JPlusWithTransitTypeAllocator(),
      TerminalQueueAllocatorWithFastTrack(defaultAirportConfig.terminalPaxTypeQueueAllocation))

    val splitsCalculator = SplitsCalculator(paxTypeQueueAllocation, defaultAirportConfig.terminalPaxSplits)

    "When I push an arrival and some splits for that arrival " >> {
      "Then I should see a message containing a FlightWithSplits representing them" >> {
        val arrivalDate = "2018-01-01"
        val arrivalTime = "00:05"
        val scheduled = s"${arrivalDate}T$arrivalTime"
        val probe = TestProbe("arrival-splits")

        val (arrivalDiffs, manifestsLiveInput, _) = TestableArrivalSplits(splitsCalculator, probe, () => SDate(scheduled)).run()
        val arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("JFK"), schDt = scheduled, feedSources = Set(LiveFeedSource))
        val paxList = List(
          PassengerInfoGenerator.passengerInfoJson(nationality = Nationality("GBR"), documentType = DocumentType("P"), issuingCountry = Nationality("GBR")),
          PassengerInfoGenerator.passengerInfoJson(nationality = Nationality("ITA"), documentType = DocumentType("P"), issuingCountry = Nationality("ITA"))
        )
        val manifests = Set(VoyageManifest(EventTypes.DC, PortCode("LHR"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival(arrivalDate), ManifestTimeOfArrival(arrivalTime), PassengerList = paxList))

        arrivalDiffs.offer(ArrivalsDiff(toUpdate = SortedMap(arrival.unique -> arrival), toRemove = Set()))

        probe.fishForMessage(3 seconds) {
          case FlightsWithSplitsDiff(flights, _) => flights.nonEmpty
        }

        manifestsLiveInput.offer(manifests.map(BestAvailableManifest(_)).toList)

        val terminalAverage = Splits(Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 100.0, None, None)), SplitSources.TerminalAverage, None, Percentage)
        val apiSplits = Splits(
          Set(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EGate, 1.6, Some(Map(Nationality("GBR") -> 0.8, Nationality("ITA") -> 0.8)), Some(Map(PaxAge(22) -> 1.6))),
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 0.4, Some(Map(Nationality("GBR") -> 0.2, Nationality("ITA") -> 0.2)), Some(Map(PaxAge(22) -> 0.4)))
          ),
          SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, None, PaxNumbers)

        val expectedSplits = Set(terminalAverage, apiSplits)
        val expected = Seq(ApiFlightWithSplits(
          arrival.copy(FeedSources = Set(LiveFeedSource, ApiFeedSource), ApiPax = Option(2)),
          expectedSplits,
          None
        ))

        probe.fishForMessage(3 seconds) {
          case fs: FlightsWithSplitsDiff =>
            val fws = fs.flightsToUpdate.map(f => f.copy(lastUpdated = None))

            fws === expected
        }

        true
      }
    }
  }

  private def refreshManifestsAndCheckQueues(scheduled: String, fws: ApiFlightWithSplits, portCode: PortCode, checkQueues: Iterable[Queues.Queue] => Boolean) = {
    val crunch = runCrunchGraph(
      TestConfig(
        airportConfig = AirportConfigs.confByPort(portCode),
        initialPortState = Option(PortState(Seq(fws), Seq(), Seq())),
        now = () => SDate(scheduled),
        refreshManifestsOnStart = true
      )
    )

    crunch.portStateTestProbe.fishForMessage(1 second) { case PortState(flights, _, _) =>
      val queues = flights.values.flatMap(fws => fws.splits.flatMap(_.splits.filter(_.paxCount > 0).map(_.queueType)))
      checkQueues(queues)
    }
  }

  "Given a flight with an EEAMR to EGate split" >> {
    "When the (LHR T2) Airport Config contains 0 EGate split and the refreshManifestsOnStart flag is set to true" >> {
      "Then the splits for the flight should no longer contain any EGate passengers" >> {
        val scheduled = "2021-06-01T12:00"
        val arrival = ArrivalGenerator.arrival("BA0001", actPax = Option(100), schDt = scheduled, terminal = T2)
        val fws = ApiFlightWithSplits(arrival, Set(Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 100, None, None)), ApiSplitsWithHistoricalEGateAndFTPercentages, None, PaxNumbers)))

        refreshManifestsAndCheckQueues(scheduled, fws, PortCode("LHR"), !_.exists(_ == EGate))

        success
      }
    }

    "When the (LHR T3) Airport Config contains a non-zero EGate split and the refreshManifestsOnStart flag is set to true" >> {
      "Then the splits for the flight should still contain EGate passengers" >> {
        val scheduled = "2021-06-01T12:00"
        val arrival = ArrivalGenerator.arrival("BA0001", actPax = Option(100), schDt = scheduled, terminal = T3)
        val fws = ApiFlightWithSplits(arrival, Set(Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 100, None, None)), ApiSplitsWithHistoricalEGateAndFTPercentages, None, PaxNumbers)))

        refreshManifestsAndCheckQueues(scheduled, fws, PortCode("LHR"), _.exists(_ == EGate))

        success
      }
    }

    "When the (STN T1) Airport Config contains a non-zero EGate split and the refreshManifestsOnStart flag is set to true" >> {
      "Then the splits for the flight should still contain EGate passengers" >> {
        val scheduled = "2021-06-01T12:00"
        val arrival = ArrivalGenerator.arrival("BA0001", actPax = Option(100), schDt = scheduled, terminal = T1)
        val fws = ApiFlightWithSplits(arrival, Set(Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 100, None, None)), ApiSplitsWithHistoricalEGateAndFTPercentages, None, PaxNumbers)))

        refreshManifestsAndCheckQueues(scheduled, fws, PortCode("STN"), _.exists(_ == EGate))

        success
      }
    }
  }

}

