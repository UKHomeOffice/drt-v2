package services.liveviews

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{MinutesContainer, PassengersMinute}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.actor.state.ArrivalsState
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, VoyageNumber}
import uk.gov.homeoffice.drt.db.PassengersHourlyRow
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{AclFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import java.sql.Timestamp
import scala.collection.immutable.SortedMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class PassengersLiveViewTest extends AnyWordSpec with Matchers {
  "minutesContainerToHourlyRow given a container with one minute" should {
    "return one row with the the passengers for that minute" in {
      val minute1 = SDate("2023-12-21T15:00")

      val container = MinutesContainer(
        List(PassengersMinute(T1, EeaDesk, minute1.millisSinceEpoch, Seq(0.5), None))
      )

      val portCode = PortCode("LHR")

      val result = PassengersLiveView.minutesContainerToHourlyRows(portCode, () => 0L)(container)

      result.toSet should ===(Set(
        PassengersHourlyRow(portCode.iata, T1.toString, EeaDesk.toString, "2023-12-21", 15, 1, new Timestamp(0L))
      ))
    }
  }

  "minutesContainerToHourlyRow given a container with minutes spanning multiple hours" should {
    "return a row for each hour with the sum of the passengers for that hour" in {
      val hour1minute1 = SDate("2023-12-21T15:00")
      val hour1minute2 = SDate("2023-12-21T15:59")
      val hour2minute1 = SDate("2023-12-21T20:00")
      val hour2minute2 = SDate("2023-12-21T20:59")

      val container = MinutesContainer(
        List(
          PassengersMinute(T1, EeaDesk, hour1minute1.millisSinceEpoch, Seq.fill(5)(0.5), None),
          PassengersMinute(T1, EeaDesk, hour1minute2.millisSinceEpoch, Seq.fill(5)(0.5), None),
          PassengersMinute(T1, EGate, hour1minute1.millisSinceEpoch, Seq.fill(4)(0.6), None),
          PassengersMinute(T1, EGate, hour1minute2.millisSinceEpoch, Seq.fill(4)(0.6), None),
          PassengersMinute(T1, EeaDesk, hour2minute1.millisSinceEpoch, Seq.fill(3)(0.5), None),
          PassengersMinute(T1, EeaDesk, hour2minute2.millisSinceEpoch, Seq.fill(3)(0.5), None),
          PassengersMinute(T1, EGate, hour2minute1.millisSinceEpoch, Seq.fill(2)(0.6), None),
          PassengersMinute(T1, EGate, hour2minute2.millisSinceEpoch, Seq.fill(2)(0.6), None),
        )
      )

      val code = PortCode("LHR")

      val result = PassengersLiveView.minutesContainerToHourlyRows(code, () => 0L)(container)

      result.toSet should ===(Set(
        PassengersHourlyRow(code.iata, T1.toString, EeaDesk.toString, "2023-12-21", 15, 10, new Timestamp(0L)),
        PassengersHourlyRow(code.iata, T1.toString, EeaDesk.toString, "2023-12-21", 20, 6, new Timestamp(0L)),
        PassengersHourlyRow(code.iata, T1.toString, EGate.toString, "2023-12-21", 15, 8, new Timestamp(0L)),
        PassengersHourlyRow(code.iata, T1.toString, EGate.toString, "2023-12-21", 20, 4, new Timestamp(0L)),
      ))
    }
  }

  "pcpMinutes" should {
    "return the number of passengers hitting the pcp each minute" in {
      val pax = 85
      val pcpStart = SDate("2024-06-27T12:00")
      val pcpMinutes = PassengersLiveView.pcpMinutes(pax, pcpStart)
      pcpMinutes should ===(Map(
        SDate("2024-06-27T12:00").millisSinceEpoch -> 20,
        SDate("2024-06-27T12:01").millisSinceEpoch -> 20,
        SDate("2024-06-27T12:02").millisSinceEpoch -> 20,
        SDate("2024-06-27T12:03").millisSinceEpoch -> 20,
        SDate("2024-06-27T12:04").millisSinceEpoch -> 5
      ))
    }
  }

  "addMinutePaxToHourAggregates" should {
    "return the number of passengers hitting the pcp each hour" in {
      val utcDate = SDate("2024-06-27T12:00").toUtcDate
      val hourly = Map(12 -> 20, 13 -> 20)
      val pcpMinutes = Map(
        SDate("2024-06-27T11:58").millisSinceEpoch -> 20,
        SDate("2024-06-27T11:59").millisSinceEpoch -> 20,
        SDate("2024-06-27T12:00").millisSinceEpoch -> 20,
        SDate("2024-06-27T12:01").millisSinceEpoch -> 5,
      )
      val result = PassengersLiveView.addMinutePaxToHourAggregates(utcDate, hourly, pcpMinutes)
      result should ===(Map(
        11 -> (20 + 20),
        12 -> (20 + 20 + 5),
        13 -> 20
      ))
    }
  }

  "populateMissingMaxPax" should {
    "return the flights with the MaxPax field populated from the baseArrivals" in {
      val baseArrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2024-06-27T12:00", maxPax = Option(85), feedSource = AclFeedSource)
      val baseArrivals = ArrivalsState(SortedMap(baseArrival.unique -> baseArrival), AclFeedSource, None)
      val missingMaxPax = ApiFlightWithSplits(apiFlight = baseArrival.copy(MaxPax = None, Estimated = Option(1L)), Set(), None)
      PassengersLiveView.populateMissingMaxPax(
        UtcDate(2024, 6, 27),
        _ => Future.successful(baseArrivals),
        Iterable(missingMaxPax),
      ).map { result =>
        result should ===(Iterable(missingMaxPax.apiFlight.copy(MaxPax = Option(85))))
      }
    }
  }

  "capacityAndPcpTimes" should {
    "return the capacity and pcp times for the flights" in {
      val flight1 = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2024-06-27T12:00", maxPax = Option(85), feedSource = AclFeedSource)
      val flight2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = "2024-06-27T12:00", maxPax = Option(85), feedSource = AclFeedSource)
      val flights = Iterable(flight1, flight2).map(flight => ApiFlightWithSplits(flight, Set(), None))
      val result = PassengersLiveView.capacityAndPcpTimes(flights)
      result should ===(Iterable(
        (85, SDate(flight1.PcpTime.getOrElse(0L))),
        (85, SDate(flight2.PcpTime.getOrElse(0L))
        )))
    }
  }

  "uniqueFlightsForDate" should {
    "return the unique flights for the date" in {
      val flight1 = ArrivalGenerator.arrival(iata = "BA0001", origin = PortCode("JFK"), schDt = "2024-06-27T12:00", feedSource = AclFeedSource)
      val codeShare1 = flight1.copy(VoyageNumber = VoyageNumber(2))
      val flight2 = ArrivalGenerator.arrival(iata = "BA0003", origin = PortCode("CDG"), schDt = "2024-06-27T12:05", maxPax = Option(85),
        feedSource = AclFeedSource)

      val flights = Iterable(flight1, codeShare1, flight2).map(flight => ApiFlightWithSplits(flight, Set(), None))

      val baseArrival = ArrivalGenerator.arrival(iata = "BA0002", schDt = "2024-06-27T12:00", maxPax = Option(85), feedSource = AclFeedSource)
      val baseArrivals = ArrivalsState(SortedMap(baseArrival.unique -> baseArrival), AclFeedSource, None)

      val eventualUniqueFlights = PassengersLiveView.uniqueFlightsForDate(
        _ => Future.successful(flights),
        _ => Future.successful(baseArrivals),
        List(AclFeedSource)
      )

      val result = Await.result(eventualUniqueFlights(UtcDate(2024, 6, 27)), 1.second)

      result.toSet should ===(Set(
        codeShare1.copy(MaxPax = Option(85)),
        flight2,
      ).map(flight => ApiFlightWithSplits(flight, Set(), None)))
    }
  }

  "populateCapacityForDate" should {
    "include capacity from flights scheduled the day before that contribute to the pcp on the date in question" in {
      val flight1 = ArrivalGenerator.arrival(iata = "BA0002", schDt = "2024-06-26T23:50", maxPax = Option(85), feedSource = AclFeedSource)
        .copy(PcpTime = Option(SDate("2024-06-26T23:58").millisSinceEpoch))
      val flight2 = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2024-06-27T12:00", maxPax = Option(85), feedSource = AclFeedSource)
      val flights = Map(
        UtcDate(2024, 6, 26) -> Iterable(ApiFlightWithSplits(flight1, Set(), None)),
        UtcDate(2024, 6, 27) -> Iterable(ApiFlightWithSplits(flight2, Set(), None))
      )

      val eventualCapacity = PassengersLiveView.capacityForDate(
        date => Future.successful(flights(date))
      )

      val result = eventualCapacity(UtcDate(2024, 6, 27))

      val expected = Map(
        T1 -> Map(
          0 -> 45,
          12 -> 85,
        )
      )

      Await.result(result, 1.second) should ===(expected)
    }
  }
}
