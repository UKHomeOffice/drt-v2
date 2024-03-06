package services.arrivals

import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, CarrierCode, FlightCodeSuffix, Operator, Passengers, UniqueArrival, VoyageNumber, Predictions => Preds}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{ForecastFeedSource, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.UtcDate

import scala.concurrent.{ExecutionContext, Future}


object MergeArrivals {
  def apply(existingMerged: UtcDate => Future[Set[UniqueArrival]],
            arrivalSources: Seq[UtcDate => Future[(Boolean, Map[UniqueArrival, Arrival])]],
           )
           (implicit ec: ExecutionContext): UtcDate => Future[(Map[UniqueArrival, Arrival], Set[UniqueArrival])] =
    (date: UtcDate) => {
      for {
        arrivalSets <- Future.sequence(arrivalSources.map(_(date)))
        existing <- existingMerged(date)
      } yield {
        mergeSets(existing, arrivalSets)
      }
    }

  def mergeSets(existingMerged: Set[UniqueArrival],
                arrivalSets: Seq[(Boolean, Map[UniqueArrival, Arrival])],
               ): (Map[UniqueArrival, Arrival], Set[UniqueArrival]) = {
    val newMerged = arrivalSets.toList match {
      case (_, startSet) :: otherSets =>
        otherSets.foldLeft(startSet) {
          case (acc, (isPrimary, arrivals)) =>
            arrivals.foldLeft(acc) {
              case (acc, (uniqueArrival, nextArrival)) =>
                acc.get(uniqueArrival) match {
                  case Some(existingArrival) =>
                    acc + (uniqueArrival -> mergeArrivals(existingArrival, nextArrival))
                  case None =>
                    if (isPrimary) acc + (uniqueArrival -> nextArrival)
                    else acc
                }
            }
        }
    }
    val removed = existingMerged -- newMerged.keySet
    (newMerged, removed)
  }

  def mergeArrivals(current: Arrival, next: Arrival): Arrival =
    next.copy(
      Operator = next.Operator.orElse(current.Operator),
      CarrierCode = current.CarrierCode,
      Estimated = next.Estimated.orElse(current.Estimated),
      Actual = next.Actual.orElse(current.Actual),
      EstimatedChox = next.EstimatedChox.orElse(current.EstimatedChox),
      ActualChox = next.ActualChox.orElse(current.ActualChox),
      Gate = next.Gate.orElse(current.Gate),
      Stand = next.Stand.orElse(current.Stand),
      MaxPax = next.MaxPax.orElse(current.MaxPax),
      RunwayID = next.RunwayID.orElse(current.RunwayID),
      BaggageReclaimId = next.BaggageReclaimId.orElse(current.BaggageReclaimId),
      FeedSources = next.FeedSources ++ current.FeedSources,
      CarrierScheduled = next.CarrierScheduled.orElse(current.CarrierScheduled),
      ScheduledDeparture = next.ScheduledDeparture.orElse(current.ScheduledDeparture),
      RedListPax = next.RedListPax.orElse(current.RedListPax),
      PassengerSources = next.PassengerSources ++ current.PassengerSources,
      FlightCodeSuffix = next.FlightCodeSuffix.orElse(current.FlightCodeSuffix),
    )
}

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

      result should ===(Map(nextWithAllValues.unique -> expectedMergedArrival), Set.empty)
    }
    "not merge an arrival where it only exists on the non-primary source" in {
      val existingMerged = Set.empty[UniqueArrival]
      val arrivalSets = Seq(
        (true, Map.empty[UniqueArrival, Arrival]),
        (false, Map[UniqueArrival, Arrival](nextWithAllValues.unique -> nextWithAllValues)),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets)

      result should ===(Map.empty, Set.empty)
    }
    "take an arrival exiting in the second set and not the first if the second set is a primary source" in {
      val existingMerged = Set.empty[UniqueArrival]
      val arrivalSets = Seq(
        (true, Map.empty[UniqueArrival, Arrival]),
        (true, Map[UniqueArrival, Arrival](nextWithAllValues.unique -> nextWithAllValues)),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets)

      result should ===(Map[UniqueArrival, Arrival](nextWithAllValues.unique -> nextWithAllValues), Set.empty)
    }
    "remove a unique arrival when it exists in the existing merged but not in a primary set" in {
      val existingMerged = Set(current.unique)
      val arrivalSets = Seq(
        (true, Map.empty[UniqueArrival, Arrival]),
      )

      val result = MergeArrivals.mergeSets(existingMerged, arrivalSets)

      result should ===(Map.empty[UniqueArrival, Arrival], Set(current.unique))
    }
  }
  "MergeArrival" should {
    "merge arrivals from multiple sources" in {
      val existingMerged = (_: UtcDate) => Future.successful(Set.empty[UniqueArrival])
      val arrivalSources = Seq(
        (_: UtcDate) => Future.successful((true, Map[UniqueArrival, Arrival](current.unique -> current))),
        (_: UtcDate) => Future.successful((false, Map[UniqueArrival, Arrival](nextWithAllValues.unique -> nextWithAllValues))),
      )

      val result = MergeArrivals(existingMerged, arrivalSources)(ExecutionContext.global)

      val expectedMergedArrival = nextWithAllValues.copy(
        FeedSources = current.FeedSources ++ nextWithAllValues.FeedSources,
        PassengerSources = current.PassengerSources ++ nextWithAllValues.PassengerSources,
      )

      result(UtcDate(2024, 6, 1)).futureValue should ===(
        (Map(nextWithAllValues.unique -> expectedMergedArrival), Set.empty)
      )
    }
  }
}
