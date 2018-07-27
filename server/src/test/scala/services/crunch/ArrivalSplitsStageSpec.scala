package services.crunch

import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.PaxTypes.{EeaMachineReadable, EeaNonMachineReadable}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import passengersplits.core.SplitsCalculator
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import server.feeds.{ManifestsFeedResponse, ManifestsFeedSuccess}
import services.SDate
import services.graphstages.{ArrivalSplitsGraphStage, DqManifests}

import scala.concurrent.duration._


object TestableArrivalSplits {
  val oneDayMillis: Int = 60 * 60 * 24 * 1000
  def groupByCodeShares(flights: Seq[ApiFlightWithSplits]): Seq[(ApiFlightWithSplits, Set[Arrival])] = flights.map(f => (f, Set(f.apiFlight)))

  def apply(splitsCalculator: SplitsCalculator, testProbe: TestProbe, now: () => SDateLike): RunnableGraph[(SourceQueueWithComplete[ArrivalsDiff], SourceQueueWithComplete[ManifestsFeedResponse], SourceQueueWithComplete[Seq[(Arrival, Option[ApiSplits])]])] = {
    val arrivalSplitsStage = new ArrivalSplitsGraphStage(
      name = "",
      optionalInitialFlights = None,
      optionalInitialManifests = None,
      splitsCalculator = splitsCalculator,
      groupFlightsByCodeShares = groupByCodeShares,
      expireAfterMillis = oneDayMillis,
      now = now,
      maxDaysToCrunch = 1)

    val arrivalsDiffSource = Source.queue[ArrivalsDiff](1, OverflowStrategy.backpressure)
    val manifestsSource = Source.queue[ManifestsFeedResponse](1, OverflowStrategy.backpressure)
    val predictionsSource = Source.queue[Seq[(Arrival, Option[ApiSplits])]](1, OverflowStrategy.backpressure)

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(
      arrivalsDiffSource.async,
      manifestsSource.async,
      predictionsSource.async
    )((_, _, _)) {

      implicit builder =>
        (
          arrivalsDiff,
          manifests,
          predictions
        ) =>
          val arrivalSplitsStageAsync = builder.add(arrivalSplitsStage.async)
          val sink = builder.add(Sink.actorRef(testProbe.ref, "complete"))

          arrivalsDiff.out ~> arrivalSplitsStageAsync.in0
          manifests.out ~> arrivalSplitsStageAsync.in1
          predictions.out ~> arrivalSplitsStageAsync.in2

          arrivalSplitsStageAsync.out ~> sink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}

class ArrivalSplitsStageSpec extends CrunchTestLike {
  val portCode = "LHR"
  val splitsProvider: (String, MilliDate) => Option[SplitRatios] = (_, _) => {
    val eeaMrToDeskSplit = SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk), 0.5)
    val eeaNmrToDeskSplit = SplitRatio(PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk), 0.5)
    Option(SplitRatios(List(eeaMrToDeskSplit, eeaNmrToDeskSplit), SplitSources.Historical))
  }
  val splitsCalculator = SplitsCalculator(portCode, splitsProvider, Set())

  "Given an arrival splits stage " +
    "When I push an arrival and some splits for that arrival " +
    "Then I should see a message containing a FlightWithSplits representing them" >> {

    val arrivalDate = "2018-01-01"
    val arrivalTime = "00:05"
    val scheduled = s"${arrivalDate}T$arrivalTime"
    val probe = TestProbe("arrival-splits")

    val (arrivalDiffs, manifestsInput, _) = TestableArrivalSplits(splitsCalculator, probe, () => SDate(scheduled)).run()
    val arrival = ArrivalGenerator.apiFlight(iata = "BA0001", schDt = scheduled)
    val paxList = List(
      PassengerInfoGenerator.passengerInfoJson(nationality = "GBR", documentType = "P", issuingCountry = "GBR"),
      PassengerInfoGenerator.passengerInfoJson(nationality = "ITA", documentType = "P", issuingCountry = "ITA")
    )
    val manifests = Set(VoyageManifest(DqEventCodes.DepartureConfirmed, portCode, "JFK", "0001", "BA", arrivalDate, arrivalTime, PassengerList = paxList))

    arrivalDiffs.offer(ArrivalsDiff(toUpdate = Set(arrival), toRemove = Set()))
    manifestsInput.offer(ManifestsFeedSuccess(DqManifests("", manifests)))

    val historicSplits = ApiSplits(
      Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50.0, None),
        ApiPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, 50.0, None)),
      SplitSources.Historical, None, Percentage)
    val apiSplits = ApiSplits(
      Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EGate, 1.0, Some(Map("GBR" -> 0.5, "ITA" -> 0.5))),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 1.0, Some(Map("GBR" -> 0.5, "ITA" -> 0.5)))),
      SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Some(DqEventCodes.DepartureConfirmed), PaxNumbers)
    val expectedSplits = Set(
      historicSplits,
      ApiSplits(Set(), SplitSources.TerminalAverage, None, Percentage),
      apiSplits)

    val expected = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, expectedSplits, None)), Set())

    probe.fishForMessage(10 seconds) {
      case fs: FlightsWithSplits =>
        fs.copy(flights = fs.flights.map(f => f.copy(lastUpdated = None))) == expected
    }

    true
  }
}