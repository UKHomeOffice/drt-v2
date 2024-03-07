package services.arrivals

import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, ArrivalsDiff, CarrierCode, FlightCodeSuffix, Operator, Passengers, UniqueArrival, VoyageNumber, Predictions => Preds}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{ForecastFeedSource, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.{DateLike, UtcDate}

import scala.concurrent.{ExecutionContext, Future}


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
    Scheduled = 1000L,
    PcpTime = None,
  )
  private val nextWithAllValues = Arrival(
    Operator = Option(Operator("BA")),
    CarrierCode = CarrierCode("BA"),
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
    FeedSources = Set(LiveFeedSource),
    CarrierScheduled = Option(50L),
    ScheduledDeparture = Option(60L),
    RedListPax = Option(70),
    PassengerSources = Map(LiveFeedSource -> Passengers(Option(100), Option(10))),
    VoyageNumber = VoyageNumber(1200),
    FlightCodeSuffix = Option(FlightCodeSuffix("A")),
    Predictions = Preds(10L, Map("a" -> 1)),
    AirportID = PortCode("LHR"),
    Terminal = T1,
    Origin = PortCode("JFK"),
    Scheduled = 1000L,
    PcpTime = Option(10L),
  )
  private val nextWithNoOptionals = Arrival(
    Operator = Option(Operator("BA")),
    CarrierCode = CarrierCode("BA"),
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
    FeedSources = Set(LiveFeedSource),
    CarrierScheduled = None,
    ScheduledDeparture = None,
    RedListPax = None,
    PassengerSources = Map(LiveFeedSource -> Passengers(Option(100), Option(10))),
    VoyageNumber = VoyageNumber(1200),
    FlightCodeSuffix = None,
    Predictions = Preds(10L, Map("a" -> 1)),
    AirportID = PortCode("LHR"),
    Terminal = T1,
    Origin = PortCode("JFK"),
    Scheduled = 1000L,
    PcpTime = None,
  )

  "mergeArrivals" should {
    "overwrite the current arrival with the next arrival's values when they're all available" in {

      val result = MergeArrivals.mergeArrivals(current, nextWithAllValues)

      result should ===(Arrival(
        Operator = nextWithAllValues.Operator,
        CarrierCode = nextWithAllValues.CarrierCode,
        Status = nextWithAllValues.Status,
        Estimated = nextWithAllValues.Estimated,
        Actual = nextWithAllValues.Actual,
        EstimatedChox = nextWithAllValues.EstimatedChox,
        ActualChox = nextWithAllValues.ActualChox,
        Gate = nextWithAllValues.Gate,
        Stand = nextWithAllValues.Stand,
        MaxPax = nextWithAllValues.MaxPax,
        RunwayID = nextWithAllValues.RunwayID,
        BaggageReclaimId = nextWithAllValues.BaggageReclaimId,
        FeedSources = current.FeedSources ++ nextWithAllValues.FeedSources,
        CarrierScheduled = nextWithAllValues.CarrierScheduled,
        ScheduledDeparture = nextWithAllValues.ScheduledDeparture,
        RedListPax = nextWithAllValues.RedListPax,
        PassengerSources = current.PassengerSources ++ nextWithAllValues.PassengerSources,
        VoyageNumber = nextWithAllValues.VoyageNumber,
        FlightCodeSuffix = nextWithAllValues.FlightCodeSuffix,
        Predictions = nextWithAllValues.Predictions,
        AirportID = nextWithAllValues.AirportID,
        Terminal = nextWithAllValues.Terminal,
        Origin = nextWithAllValues.Origin,
        Scheduled = nextWithAllValues.Scheduled,
        PcpTime = nextWithAllValues.PcpTime,
      ))
    }
    "overwrite the current arrival with the next arrival's values falling back on current value where next doesn't have one" in {

      val result = MergeArrivals.mergeArrivals(current, nextWithNoOptionals)

      result should ===(Arrival(
        Operator = nextWithNoOptionals.Operator,
        CarrierCode = nextWithNoOptionals.CarrierCode,
        Status = nextWithNoOptionals.Status,
        Estimated = current.Estimated,
        Actual = current.Actual,
        EstimatedChox = current.EstimatedChox,
        ActualChox = current.ActualChox,
        Gate = current.Gate,
        Stand = current.Stand,
        MaxPax = current.MaxPax,
        RunwayID = current.RunwayID,
        BaggageReclaimId = current.BaggageReclaimId,
        FeedSources = current.FeedSources ++ nextWithNoOptionals.FeedSources,
        CarrierScheduled = current.CarrierScheduled,
        ScheduledDeparture = current.ScheduledDeparture,
        RedListPax = current.RedListPax,
        PassengerSources = current.PassengerSources ++ nextWithNoOptionals.PassengerSources,
        VoyageNumber = nextWithNoOptionals.VoyageNumber,
        FlightCodeSuffix = current.FlightCodeSuffix,
        Predictions = nextWithNoOptionals.Predictions,
        AirportID = nextWithNoOptionals.AirportID,
        Terminal = nextWithNoOptionals.Terminal,
        Origin = nextWithNoOptionals.Origin,
        Scheduled = nextWithNoOptionals.Scheduled,
        PcpTime = current.PcpTime,
      ))
    }
  }

  "mergeSets" should {
    "merge arrival where one exists in the primary & non-primary source" in {
      val existingMerged = Set.empty[UniqueArrival]
      val arrivalSets = Seq(
        (true, Map[UniqueArrival, Arrival](current.unique -> current)),
        (false, Map[UniqueArrival, Arrival](nextWithAllValues.unique -> nextWithAllValues)),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets)

      val expectedMergedArrival = nextWithAllValues.copy(
        FeedSources = current.FeedSources ++ nextWithAllValues.FeedSources,
        PassengerSources = current.PassengerSources ++ nextWithAllValues.PassengerSources,
      )

      result should ===(
        ArrivalsDiff(Map(nextWithAllValues.unique -> expectedMergedArrival), Set.empty)
      )
    }
    "not merge an arrival where it only exists on the non-primary source" in {
      val existingMerged = Set.empty[UniqueArrival]
      val arrivalSets = Seq(
        (true, Map.empty[UniqueArrival, Arrival]),
        (false, Map[UniqueArrival, Arrival](nextWithAllValues.unique -> nextWithAllValues)),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets)

      result should ===(ArrivalsDiff(Iterable.empty, Set.empty))
    }
    "take an arrival exiting in the second set and not the first if the second set is a primary source" in {
      val existingMerged = Set.empty[UniqueArrival]
      val arrivalSets = Seq(
        (true, Map.empty[UniqueArrival, Arrival]),
        (true, Map[UniqueArrival, Arrival](nextWithAllValues.unique -> nextWithAllValues)),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets)

      result should ===(
        ArrivalsDiff(Map[UniqueArrival, Arrival](nextWithAllValues.unique -> nextWithAllValues), Set.empty)
      )
    }
    "remove a unique arrival when it exists in the existing merged but not in a primary set" in {
      val existingMerged = Set(current.unique)
      val arrivalSets = Seq(
        (true, Map.empty[UniqueArrival, Arrival]),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets)

      result should ===(
        ArrivalsDiff(Map.empty[UniqueArrival, Arrival], Set(current.unique))
      )
    }
  }
  "MergeArrival" should {
    "merge arrivals from multiple sources" in {
      val existingMerged = (_: UtcDate) => Future.successful(Set.empty[UniqueArrival])
      val arrivalSources = Seq(
        (_: DateLike) => Future.successful((true, Map[UniqueArrival, Arrival](current.unique -> current))),
        (_: DateLike) => Future.successful((false, Map[UniqueArrival, Arrival](nextWithAllValues.unique -> nextWithAllValues))),
      )

      val result = MergeArrivals(existingMerged, arrivalSources)(ExecutionContext.global)

      val expectedMergedArrival = nextWithAllValues.copy(
        FeedSources = current.FeedSources ++ nextWithAllValues.FeedSources,
        PassengerSources = current.PassengerSources ++ nextWithAllValues.PassengerSources,
      )

      result(UtcDate(2024, 6, 1)).futureValue should ===(
        ArrivalsDiff(Map(nextWithAllValues.unique -> expectedMergedArrival), Set.empty)
      )
    }
    "propagate exceptions in the existing merged provider" in {
      val existingMerged = (_: UtcDate) => Future.failed(new Exception("Boom"))
      val arrivalSources = Seq(
        (_: DateLike) => Future.successful((true, Map[UniqueArrival, Arrival](current.unique -> current))),
        (_: DateLike) => Future.successful((false, Map[UniqueArrival, Arrival](nextWithAllValues.unique -> nextWithAllValues))),
      )

      val result = MergeArrivals(existingMerged, arrivalSources)(ExecutionContext.global)

      result(UtcDate(2024, 6, 1)).failed.futureValue.getMessage should ===("Boom")
    }
    "propagate exceptions in the arrival sources providers" in {
      val existingMerged = (_: UtcDate) => Future.successful(Set.empty[UniqueArrival])
      val arrivalSources = Seq(
        (_: DateLike) => Future.failed(new Exception("Boom")),
        (_: DateLike) => Future.successful((false, Map[UniqueArrival, Arrival](nextWithAllValues.unique -> nextWithAllValues))),
      )

      val result = MergeArrivals(existingMerged, arrivalSources)(ExecutionContext.global)

      result(UtcDate(2024, 6, 1)).failed.futureValue.getMessage should ===("Boom")
    }
  }
}
