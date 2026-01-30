package services.arrivals

import drt.shared.ArrivalGenerator
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.arrivals.MergeArrivals.FeedArrivalSet
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, ArrivalsDiff, CarrierCode, FlightCodeSuffix, Operator, Passengers, Predictions, UniqueArrival, VoyageNumber, Predictions => Preds}
import uk.gov.homeoffice.drt.ports.Terminals.{A1, A2, T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{DateLike, LocalDate, UtcDate}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}


class MergeArrivalsSpec extends AnyWordSpec with Matchers {
  private val current = Arrival(
    Operator = Option(Operator("BA")),
    CarrierCode = CarrierCode("BA"),
    Status = ArrivalStatus("Scheduled"),
    Estimated = Option(1L),
    Actual = Option(2L),
    EstimatedChox = Option(3L),
    ActualChox = Option(4L),
    Gate = Option("G1"),
    Stand = Option("S1"),
    MaxPax = Option(100),
    RunwayID = Option("R1"),
    BaggageReclaimId = Option("B1"),
    FeedSources = Set(ForecastFeedSource),
    CarrierScheduled = Option(5L),
    ScheduledDeparture = Option(6L),
    RedListPax = Option(7),
    PassengerSources = Map(ForecastFeedSource -> Passengers(Option(150), Option(2))),
    VoyageNumber = VoyageNumber(1200),
    FlightCodeSuffix = Option(FlightCodeSuffix("A")),
    Predictions = Preds(0L, Map()),
    AirportID = PortCode("LHR"),
    Terminal = T1,
    Origin = PortCode("JFK"),
    PreviousPort = Option(PortCode("JFK")),
    Scheduled = 1000L,
    PcpTime = None,
  )

  private def updateWithAllOptionals(source: FeedSource) = current.copy(
    Status = ArrivalStatus("Landed"),
    Estimated = Option(10L),
    Actual = Option(20L),
    EstimatedChox = Option(30L),
    ActualChox = Option(40L),
    Gate = Option("G10"),
    Stand = Option("S10"),
    MaxPax = Option(1000),
    RunwayID = Option("R10"),
    BaggageReclaimId = Option("B10"),
    FeedSources = Set(source),
    CarrierScheduled = Option(50L),
    ScheduledDeparture = Option(60L),
    RedListPax = Option(70),
    PassengerSources = Map(source -> Passengers(Option(100), Option(10))),
    FlightCodeSuffix = Option(FlightCodeSuffix("A")),
    Predictions = Preds(10L, Map("a" -> 1)),
    PreviousPort = Option(PortCode("ABZ")),
  )

  private def updateWithNoOptionals(source: FeedSource) = current.copy(
    Status = ArrivalStatus("Landed"),
    Estimated = None,
    Actual = None,
    EstimatedChox = None,
    ActualChox = None,
    Gate = None,
    Stand = None,
    MaxPax = None,
    RunwayID = None,
    BaggageReclaimId = None,
    FeedSources = Set(source),
    CarrierScheduled = None,
    ScheduledDeparture = None,
    RedListPax = None,
    PassengerSources = Map(source -> Passengers(Option(100), Option(10))),
    FlightCodeSuffix = None,
    Predictions = Preds(10L, Map("a" -> 1)),
    PreviousPort = None,
  )

  "mergeArrivals" should {
    "overwrite the current arrival with the next arrival's values when they're all available" in {

      val result = MergeArrivals.mergeArrivals(current, updateWithAllOptionals(LiveFeedSource))

      result should ===(Arrival(
        Operator = updateWithAllOptionals(LiveFeedSource).Operator,
        CarrierCode = updateWithAllOptionals(LiveFeedSource).CarrierCode,
        Status = updateWithAllOptionals(LiveFeedSource).Status,
        Estimated = updateWithAllOptionals(LiveFeedSource).Estimated,
        Actual = updateWithAllOptionals(LiveFeedSource).Actual,
        EstimatedChox = updateWithAllOptionals(LiveFeedSource).EstimatedChox,
        ActualChox = updateWithAllOptionals(LiveFeedSource).ActualChox,
        Gate = updateWithAllOptionals(LiveFeedSource).Gate,
        Stand = updateWithAllOptionals(LiveFeedSource).Stand,
        MaxPax = updateWithAllOptionals(LiveFeedSource).MaxPax,
        RunwayID = updateWithAllOptionals(LiveFeedSource).RunwayID,
        BaggageReclaimId = updateWithAllOptionals(LiveFeedSource).BaggageReclaimId,
        FeedSources = current.FeedSources ++ updateWithAllOptionals(LiveFeedSource).FeedSources,
        CarrierScheduled = updateWithAllOptionals(LiveFeedSource).CarrierScheduled,
        ScheduledDeparture = updateWithAllOptionals(LiveFeedSource).ScheduledDeparture,
        RedListPax = updateWithAllOptionals(LiveFeedSource).RedListPax,
        PassengerSources = current.PassengerSources ++ updateWithAllOptionals(LiveFeedSource).PassengerSources,
        VoyageNumber = updateWithAllOptionals(LiveFeedSource).VoyageNumber,
        FlightCodeSuffix = updateWithAllOptionals(LiveFeedSource).FlightCodeSuffix,
        Predictions = updateWithAllOptionals(LiveFeedSource).Predictions,
        AirportID = updateWithAllOptionals(LiveFeedSource).AirportID,
        Terminal = updateWithAllOptionals(LiveFeedSource).Terminal,
        Origin = updateWithAllOptionals(LiveFeedSource).Origin,
        PreviousPort = updateWithAllOptionals(LiveFeedSource).PreviousPort,
        Scheduled = updateWithAllOptionals(LiveFeedSource).Scheduled,
        PcpTime = updateWithAllOptionals(LiveFeedSource).PcpTime,
      ))
    }
    "overwrite the current arrival with the next arrival's values falling back on current value where next doesn't have one" in {

      val result = MergeArrivals.mergeArrivals(current, updateWithNoOptionals(LiveFeedSource))
      result should ===(Arrival(
        Operator = updateWithNoOptionals(LiveFeedSource).Operator,
        CarrierCode = updateWithNoOptionals(LiveFeedSource).CarrierCode,
        Status = updateWithNoOptionals(LiveFeedSource).Status,
        Estimated = current.Estimated,
        Actual = current.Actual,
        EstimatedChox = current.EstimatedChox,
        ActualChox = current.ActualChox,
        Gate = current.Gate,
        Stand = current.Stand,
        MaxPax = current.MaxPax,
        RunwayID = current.RunwayID,
        BaggageReclaimId = current.BaggageReclaimId,
        FeedSources = current.FeedSources ++ updateWithNoOptionals(LiveFeedSource).FeedSources,
        CarrierScheduled = current.CarrierScheduled,
        ScheduledDeparture = current.ScheduledDeparture,
        RedListPax = current.RedListPax,
        PassengerSources = current.PassengerSources ++ updateWithNoOptionals(LiveFeedSource).PassengerSources,
        VoyageNumber = updateWithNoOptionals(LiveFeedSource).VoyageNumber,
        FlightCodeSuffix = current.FlightCodeSuffix,
        Predictions = updateWithNoOptionals(LiveFeedSource).Predictions,
        AirportID = updateWithNoOptionals(LiveFeedSource).AirportID,
        Terminal = updateWithNoOptionals(LiveFeedSource).Terminal,
        Origin = updateWithNoOptionals(LiveFeedSource).Origin,
        PreviousPort = current.PreviousPort,
        Scheduled = updateWithNoOptionals(LiveFeedSource).Scheduled,
        PcpTime = current.PcpTime,
      ))
    }
  }

  "mergeSets" should {
    "merge arrival where one exists in the primary & non-primary source" in {
      val existingMerged = Set.empty[UniqueArrival]
      val arrivalSets = Seq(
        FeedArrivalSet(isPrimary = true, None, Map[UniqueArrival, Arrival](current.unique -> current)),
        FeedArrivalSet(isPrimary = false, None, Map[UniqueArrival, Arrival](updateWithAllOptionals(LiveFeedSource).unique -> updateWithAllOptionals(LiveFeedSource))),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets, identity)

      val expectedMergedArrival = updateWithAllOptionals(LiveFeedSource).copy(
        FeedSources = current.FeedSources ++ updateWithAllOptionals(LiveFeedSource).FeedSources,
        PassengerSources = current.PassengerSources ++ updateWithAllOptionals(LiveFeedSource).PassengerSources,
      )

      result should ===(
        ArrivalsDiff(Map(updateWithAllOptionals(LiveFeedSource).unique -> expectedMergedArrival), Set.empty)
      )
    }

    "not merge an arrival where it only exists on the non-primary source" in {
      val existingMerged = Set.empty[UniqueArrival]
      val arrivalSets = Seq(
        FeedArrivalSet(isPrimary = true, None, Map.empty[UniqueArrival, Arrival]),
        FeedArrivalSet(isPrimary = false, None, Map[UniqueArrival, Arrival](updateWithAllOptionals(LiveFeedSource).unique -> updateWithAllOptionals(LiveFeedSource))),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets, identity)

      result should ===(ArrivalsDiff(Iterable.empty, Set.empty))
    }
    "take an arrival existing in the second set and not the first if the second set is a primary source" in {
      val existingMerged = Set.empty[UniqueArrival]
      val arrivalSets = Seq(
        FeedArrivalSet(isPrimary = true, None, Map.empty[UniqueArrival, Arrival]),
        FeedArrivalSet(isPrimary = true, None, Map[UniqueArrival, Arrival](updateWithAllOptionals(LiveFeedSource).unique -> updateWithAllOptionals(LiveFeedSource))),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets, identity)

      result should ===(
        ArrivalsDiff(Map[UniqueArrival, Arrival](updateWithAllOptionals(LiveFeedSource).unique -> updateWithAllOptionals(LiveFeedSource)), Set.empty)
      )
    }
    "remove a unique arrival when it exists in the existing merged but not in a primary set" in {
      val existingMerged = Set(current.unique)
      val arrivalSets = Seq(
        FeedArrivalSet(isPrimary = true, None, Map.empty[UniqueArrival, Arrival]),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets, identity)

      result should ===(
        ArrivalsDiff(Map.empty[UniqueArrival, Arrival], Set(current.unique))
      )
    }
    "at EDI move an arrival from A2 to A1 when its baggage belt number is 1, 2 or 3" in {
      val a2BaggageBelt = Option("7")
      val a1BaggageBelt = Option("2")
      val arrivalA2 = current.copy(Terminal = A2, BaggageReclaimId = a2BaggageBelt)
      val arrivalWithA1Baggage = arrivalA2.copy(BaggageReclaimId = a1BaggageBelt)
      val arrivalSets = Seq(
        FeedArrivalSet(isPrimary = true, None, Map[UniqueArrival, Arrival](arrivalWithA1Baggage.unique -> arrivalWithA1Baggage)),
      )

      val result = MergeArrivals.mergeSets(Set(arrivalA2.unique), arrivalSets, EdiArrivalsTerminalAdjustments.adjust)

      val arrivalA1 = arrivalWithA1Baggage.copy(Terminal = A1)
      result should ===(
        ArrivalsDiff(Map[UniqueArrival, Arrival](arrivalA1.unique -> arrivalA1), Set(arrivalA2.unique))
      )

    }
    "at EDI move an arrival from A1 to A2 when its baggage belt number is 7" in {
      val a2BaggageBelt = Option("7")
      val a1BaggageBelt = Option("2")
      val arrivalA1 = current.copy(Terminal = A1, BaggageReclaimId = a1BaggageBelt)
      val arrivalWithA2Baggage = arrivalA1.copy(BaggageReclaimId = a2BaggageBelt)
      val arrivalSets = Seq(
        FeedArrivalSet(isPrimary = true, None, Map[UniqueArrival, Arrival](arrivalWithA2Baggage.unique -> arrivalWithA2Baggage)),
      )

      val result = MergeArrivals.mergeSets(Set(arrivalA1.unique), arrivalSets, EdiArrivalsTerminalAdjustments.adjust)

      val arrivalA2 = arrivalWithA2Baggage.copy(Terminal = A2)
      result should ===(
        ArrivalsDiff(Map[UniqueArrival, Arrival](arrivalA2.unique -> arrivalA2), Set(arrivalA1.unique))
      )

    }
    "merge a lower-priority non-primary arrival when the arrivals exists in a higher order primary source" in {
      val existingMerged = Set.empty[UniqueArrival]
      val ciriumArrival = updateWithAllOptionals(LiveBaseFeedSource)
      val liveArrival = updateWithNoOptionals(LiveFeedSource)
      val arrivalSets = Seq(
        FeedArrivalSet(isPrimary = false, None, Map(ciriumArrival.unique -> ciriumArrival)),
        FeedArrivalSet(isPrimary = true, None, Map(liveArrival.unique -> liveArrival)),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets, identity)

      result should ===(
        ArrivalsDiff(Map(
          ciriumArrival.unique -> ciriumArrival.copy(
            FeedSources = liveArrival.FeedSources ++ ciriumArrival.FeedSources,
            PassengerSources = liveArrival.PassengerSources ++ ciriumArrival.PassengerSources,
          )),
          Set.empty[UniqueArrival]
        )
      )
    }
    "take the status from a non-primary if the primary's status is empty" in {
      val existingMerged = Set.empty[UniqueArrival]
      val ciriumArrival = updateWithAllOptionals(LiveBaseFeedSource).copy(Status = ArrivalStatus("Cancelled"))
      val liveArrival = updateWithNoOptionals(LiveFeedSource).copy(Status = ArrivalStatus(""))
      val arrivalSets = Seq(
        FeedArrivalSet(isPrimary = false, None, Map(ciriumArrival.unique -> ciriumArrival)),
        FeedArrivalSet(isPrimary = true, None, Map(liveArrival.unique -> liveArrival)),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets, identity)

      result should ===(
        ArrivalsDiff(Map(
          ciriumArrival.unique -> ciriumArrival.copy(
            FeedSources = liveArrival.FeedSources ++ ciriumArrival.FeedSources,
            PassengerSources = liveArrival.PassengerSources ++ ciriumArrival.PassengerSources,
          )),
          Set.empty[UniqueArrival]
        )
      )
    }
    "merge a non-primary arrival with a schedule match tolerance when a fuzzy match exists in a higher order primary source" in {
      val existingMerged = Set.empty[UniqueArrival]
      val ciriumArrival = updateWithAllOptionals(LiveBaseFeedSource)
      val ciriumArrivalFuzzy = ciriumArrival.copy(Scheduled = ciriumArrival.Scheduled + 2.minutes.toMillis)
      val liveArrival = updateWithNoOptionals(LiveFeedSource)
      val arrivalSets = Seq(
        FeedArrivalSet(isPrimary = false, Option(5.minutes), Map(ciriumArrivalFuzzy.unique -> ciriumArrivalFuzzy)),
        FeedArrivalSet(isPrimary = true, None, Map(liveArrival.unique -> liveArrival)),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets, identity)

      result should ===(
        ArrivalsDiff(Map(
          updateWithAllOptionals(LiveFeedSource).unique -> updateWithAllOptionals(LiveFeedSource).copy(
            FeedSources = liveArrival.FeedSources ++ ciriumArrival.FeedSources,
            PassengerSources = liveArrival.PassengerSources ++ ciriumArrival.PassengerSources,
          )),
          Set.empty[UniqueArrival]
        )
      )
    }
  }

  "MergeArrival" should {
    "merge arrivals from multiple sources" in {
      val existingMerged = (_: Terminal, _: UtcDate) => Future.successful(Set.empty[UniqueArrival])
      val arrivalSources = Seq(
        (_: DateLike, _: Terminal) => Future.successful(FeedArrivalSet(isPrimary = true, None, Map[UniqueArrival, Arrival](current.unique -> current))),
        (_: DateLike, _: Terminal) => Future.successful(FeedArrivalSet(isPrimary = false, None, Map[UniqueArrival, Arrival](updateWithAllOptionals(LiveFeedSource).unique -> updateWithAllOptionals(LiveFeedSource)))),
      )

      val result = MergeArrivals(existingMerged, arrivalSources, identity)(ExecutionContext.global)

      val expectedMergedArrival = updateWithAllOptionals(LiveFeedSource).copy(
        FeedSources = current.FeedSources ++ updateWithAllOptionals(LiveFeedSource).FeedSources,
        PassengerSources = current.PassengerSources ++ updateWithAllOptionals(LiveFeedSource).PassengerSources,
      )

      result(T1, UtcDate(2024, 6, 1)).futureValue should ===(
        ArrivalsDiff(Map(updateWithAllOptionals(LiveFeedSource).unique -> expectedMergedArrival), Set.empty)
      )
    }
    "propagate exceptions in the existing merged provider" in {
      val existingMerged = (_: Terminal, _: UtcDate) => Future.failed(new Exception("Boom"))
      val arrivalSources = Seq(
        (_: DateLike, _: Terminal) => Future.successful(FeedArrivalSet(isPrimary = true, None, Map[UniqueArrival, Arrival](current.unique -> current))),
        (_: DateLike, _: Terminal) => Future.successful(FeedArrivalSet(isPrimary = false, None, Map[UniqueArrival, Arrival](updateWithAllOptionals(LiveFeedSource).unique -> updateWithAllOptionals(LiveFeedSource)))),
      )

      val result = MergeArrivals(existingMerged, arrivalSources, identity)(ExecutionContext.global)

      result(T1, UtcDate(2024, 6, 1)).failed.futureValue.getMessage should ===("Boom")
    }
    "propagate exceptions in the arrival sources providers" in {
      val existingMerged = (_: Terminal, _: UtcDate) => Future.successful(Set.empty[UniqueArrival])
      val arrivalSources = Seq(
        (_: DateLike, _: Terminal) => Future.failed(new Exception("Boom")),
        (_: DateLike, _: Terminal) => Future.successful(FeedArrivalSet(isPrimary = false, None, Map[UniqueArrival, Arrival](updateWithAllOptionals(LiveFeedSource).unique -> updateWithAllOptionals(LiveFeedSource)))),
      )

      val result = MergeArrivals(existingMerged, arrivalSources, identity)(ExecutionContext.global)

      result(T1, UtcDate(2024, 6, 1)).failed.futureValue.getMessage should ===("Boom")
    }
  }

  "processingRequestToArrivalsDiff" should {
    val system = ActorSystem("processingRequestToArrivalsDiff")
    implicit val mat = Materializer.matFromSystem(system)

    var callCount = 0
    val arrival = ArrivalGenerator.arrival(iata = "BA0001").toArrival(LiveFeedSource)
    val mergeArrivalsForDate: (Terminal, UtcDate) => Future[ArrivalsDiff] =
      (_: Terminal, _: UtcDate) => Future.successful(ArrivalsDiff(Iterable(arrival), Iterable.empty[UniqueArrival]))
    val setupPcpTimes: Seq[Arrival] => Future[Seq[Arrival]] =
      arrivals => Future.successful(arrivals.map(a => a.copy(PcpTime = Some(a.Scheduled))))
    val addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff] =
      arrivalsDiff => {
        callCount += 1
        Future.successful(arrivalsDiff)
      }

    "call addArrivalPredictions when the request date is not earlier than today" in {
      val flow = MergeArrivals.processingRequestToArrivalsDiff(mergeArrivalsForDate, setupPcpTimes, addArrivalPredictions, () => LocalDate(2025, 5, 13))
      callCount = 0
      val runnable = Source(Seq(TerminalUpdateRequest(T1, LocalDate(2025, 5, 13))))
        .via(flow)
        .runWith(Sink.ignore)

      Await.ready(runnable, 1.second)

      assert(callCount == 1)
    }

    "not call addArrivalPredictions when the request date is not earlier than today" in {
      val flow = MergeArrivals.processingRequestToArrivalsDiff(mergeArrivalsForDate, setupPcpTimes, addArrivalPredictions, () => LocalDate(2025, 5, 13))
      callCount = 0
      val runnable = Source(Seq(TerminalUpdateRequest(T1, LocalDate(2025, 5, 12))))
        .via(flow)
        .runWith(Sink.ignore)

      Await.ready(runnable, 1.second)

      assert(callCount == 0)
    }
  }

}
