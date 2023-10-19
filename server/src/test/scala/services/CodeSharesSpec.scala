package services

import controllers.ArrivalGenerator.arrival
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import uk.gov.homeoffice.drt.ports._


class CodeSharesSpec extends Specification {

  import drt.shared.CodeShares._

  val paxFeedSourceOrder: List[FeedSource] = List(LiveFeedSource, ApiFeedSource)

  "Given one flight " +
    "When we ask for unique arrivals " +
    "Then we should see that flight with zero code shares " >> {
    val flight: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(100), terminal = T1, origin = PortCode("JFK"))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival], paxFeedSourceOrder)(Seq(flight))

    val expected = List((flight, Seq()))

    result === expected
  }

  private def aclPassengers(paxCount: Int): Map[FeedSource, Passengers] = {
    Map(AclFeedSource -> Passengers(Option(paxCount), None))
  }

  "Given two flights which are codeshares of each other " +
    "When we ask for unique flights " +
    "Then we should see a tuple of only one flight with its code share" >> {
    val flight1: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(100), terminal = T1, origin = PortCode("JFK"))
    val flight2: Arrival = arrival(iata = "AA8778", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(150), terminal = T1, origin = PortCode("JFK"))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival], paxFeedSourceOrder)(Seq(flight1, flight2))

    val expected = List((flight2, Seq(flight1.flightCodeString)))

    result === expected
  }

  "Given three flights which are codeshares of each other " +
    "When we ask for unique flights " +
    "Then we should see a tuple of only one flight with its two code shares" >> {
    val flight1: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(100), terminal = T1, origin = PortCode("JFK"))
    val flight2: Arrival = arrival(iata = "AA8778", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(150), terminal = T1, origin = PortCode("JFK"))
    val flight3: Arrival = arrival(iata = "ZZ5566", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(175), terminal = T1, origin = PortCode("JFK"))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival], paxFeedSourceOrder)(Seq(flight1, flight2, flight3))

    val expected = List((flight3, Seq(flight1.flightCodeString, flight2.flightCodeString)))

    result === expected
  }

  "Given 3 flight, where there is a set of code shares and one unique flight " +
    "When we ask for unique flights " +
    "Then we should see a 3 tuples; one flight with no code shares and 2 flights with their two code shares" >> {
    val flightCS1a: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(100), terminal = T1, origin = PortCode("JFK"))
    val flightCS1b: Arrival = arrival(iata = "AA8778", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(150), terminal = T1, origin = PortCode("JFK"))
    val flight: Arrival = arrival(iata = "KL1010", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(175), terminal = T2, origin = PortCode("JFK"))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival], paxFeedSourceOrder)(Seq(flightCS1a, flightCS1b, flight)).toSet

    val expected = Set(
      (flightCS1b, Seq(flightCS1a.flightCodeString)),
      (flight, Seq())
    )

    result === expected
  }

  "Given two flights with the same scheduled time, the same terminal, but different origins " +
    "When we ask for unique flights " +
    "Then we should see two tuples, each with one of the flights and no code shares" >> {
    val flight1: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(100), terminal = T1, origin = PortCode("JFK"))
    val flight2: Arrival = arrival(iata = "AA8778", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(150), terminal = T1, origin = PortCode("CDG"))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival], paxFeedSourceOrder)(Seq(flight1, flight2)).toSet

    val expected = Set(
      (flight1, Seq()),
      (flight2, Seq())
    )

    result === expected
  }

  "Given two flights with the same scheduled time, the same origins, but different terminals " +
    "When we ask for unique flights " +
    "Then we should see two tuples, each with one of the flights and no code shares" >> {
    val flight1: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(100), terminal = T1, origin = PortCode("JFK"))
    val flight2: Arrival = arrival(iata = "AA8778", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(150), terminal = T2, origin = PortCode("JFK"))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival], paxFeedSourceOrder)(Seq(flight1, flight2)).toSet

    val expected = Set(
      (flight1, Seq()),
      (flight2, Seq())
    )

    result === expected
  }

  "Given two flights with the same origins, the same different terminals, but different scheduled times" +
    "When we ask for unique flights " +
    "Then we should see two tuples, each with one of the flights and no code shares" >> {
    val flight1: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:30Z", passengerSources = aclPassengers(100), terminal = T1, origin = PortCode("JFK"))
    val flight2: Arrival = arrival(iata = "AA8778", schDt = "2016-01-01T10:25Z", passengerSources = aclPassengers(150), terminal = T1, origin = PortCode("JFK"))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival], paxFeedSourceOrder)(Seq(flight1, flight2)).toSet

    val expected = Set(
      (flight1, Seq()),
      (flight2, Seq())
    )

    result === expected
  }

  "Given two codeshare flights where one has a higher number of passengers but no live API data, and the other does has live API data" should {
    "The main flight should be the one with the live API data" in {
      val apiPaxSource = Map(ApiFeedSource -> Passengers(Option(100), None))
      val flight1: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:30Z", passengerSources = aclPassengers(100) ++ apiPaxSource, terminal = T1, origin = PortCode("JFK"))
      val flight2: Arrival = arrival(iata = "AA8778", schDt = "2016-01-01T10:30Z", passengerSources = aclPassengers(150), terminal = T1, origin = PortCode("JFK"))

      val result = uniqueArrivalsWithCodeShares(identity[Arrival], paxFeedSourceOrder)(Seq(flight1, flight2)).toSet

      val expected = Set(
        (flight1, Seq("AA8778")),
      )

      result === expected
    }
  }
}
