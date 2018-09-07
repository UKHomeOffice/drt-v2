package services

import drt.shared.{ApiFeedSource, Arrival, LiveFeedSource}
import org.specs2.mutable.Specification
import controllers.ArrivalGenerator.apiFlight


class CodeSharesSpec extends Specification {

  import drt.shared.CodeShares._

  "Given one flight " +
    "When we ask for unique arrivals " +
    "Then we should see that flight with zero code shares " >> {
    val flight: Arrival = apiFlight(flightId = Option(1), iata = "BA0001", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(100))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flight))

    val expected = List((flight, Set()))

    result === expected
  }

  "Given two flights which are codeshares of each other " +
    "When we ask for unique flights " +
    "Then we should see a tuple of only one flight with its code share" >> {
    val flight1: Arrival = apiFlight(flightId = Option(1), iata = "BA0001", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(100))
    val flight2: Arrival = apiFlight(flightId = Option(2), iata = "AA8778", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(150))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flight1, flight2))

    val expected = List((flight2, Set(flight1)))

    result === expected
  }

  "Given three flights which are codeshares of each other " +
    "When we ask for unique flights " +
    "Then we should see a tuple of only one flight with its two code shares" >> {
    val flight1: Arrival = apiFlight(flightId = Option(1), iata = "BA0001", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(100))
    val flight2: Arrival = apiFlight(flightId = Option(2), iata = "AA8778", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(150))
    val flight3: Arrival = apiFlight(flightId = Option(3), iata = "ZZ5566", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(175))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flight1, flight2, flight3))

    val expected = List((flight3, Set(flight1, flight2)))

    result === expected
  }

  "Given 5 flight, where there are 2 sets of code shares and one unique flight " +
    "When we ask for unique flights " +
    "Then we should see a 3 tuples; one flight with no code shares and 2 flights with their two code shares" >> {
    val flightCS1a: Arrival = apiFlight(flightId = Option(1), iata = "BA0001", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(100))
    val flightCS1b: Arrival = apiFlight(flightId = Option(2), iata = "AA8778", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(150))
    val flightCS2a: Arrival = apiFlight(flightId = Option(3), iata = "ZZ5566", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "CDG", actPax = Option(55))
    val flightCS2b: Arrival = apiFlight(flightId = Option(4), iata = "TG8000", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "CDG", actPax = Option(180))
    val flight: Arrival = apiFlight(flightId = Option(5), iata = "KL1010", schDt = "2016-01-01T10:25Z", terminal = "T2", origin = "JFK", actPax = Option(175))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flightCS1a, flightCS1b, flightCS2a, flightCS2b, flight)).toSet

    val expected = Set(
      (flightCS1b, Set(flightCS1a)),
      (flightCS2b, Set(flightCS2a)),
      (flight, Set())
    )

    result === expected
  }

  "Given two flights with the same scheduled time, the same terminal, but different origins " +
    "When we ask for unique flights " +
    "Then we should see two tuples, each with one of the flights and no code shares" >> {
    val flight1: Arrival = apiFlight(flightId = Option(1), iata = "BA0001", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(100))
    val flight2: Arrival = apiFlight(flightId = Option(2), iata = "AA8778", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "CDG", actPax = Option(150))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flight1, flight2)).toSet

    val expected = Set(
      (flight1, Set()),
      (flight2, Set())
    )

    result === expected
  }

  "Given two flights with the same scheduled time, the same origins, but different terminals " +
    "When we ask for unique flights " +
    "Then we should see two tuples, each with one of the flights and no code shares" >> {
    val flight1: Arrival = apiFlight(flightId = Option(1), iata = "BA0001", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(100))
    val flight2: Arrival = apiFlight(flightId = Option(2), iata = "AA8778", schDt = "2016-01-01T10:25Z", terminal = "T2", origin = "JFK", actPax = Option(150))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flight1, flight2)).toSet

    val expected = Set(
      (flight1, Set()),
      (flight2, Set())
    )

    result === expected
  }

  "Given two flights with the same origins, the same different terminals, but different scheduled times" +
    "When we ask for unique flights " +
    "Then we should see two tuples, each with one of the flights and no code shares" >> {
    val flight1: Arrival = apiFlight(flightId = Option(1), iata = "BA0001", schDt = "2016-01-01T10:30Z", terminal = "T1", origin = "JFK", actPax = Option(100))
    val flight2: Arrival = apiFlight(flightId = Option(2), iata = "AA8778", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(150))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flight1, flight2)).toSet

    val expected = Set(
      (flight1, Set()),
      (flight2, Set())
    )

    result === expected
  }

  "Codeshares with API data" should {

    "Given three similar flights all with API data "+
      "When we ask for unique flights "+
      "Then we should see three tuple of only of flights with no codeshares" in {
      val flight1: Arrival = apiFlight(flightId = Option(1), iata = "BA0001", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(100), feedSources = Set(ApiFeedSource, LiveFeedSource))
      val flight2: Arrival = apiFlight(flightId = Option(2), iata = "AA8778", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(150), feedSources = Set(ApiFeedSource, LiveFeedSource))
      val flight3: Arrival = apiFlight(flightId = Option(3), iata = "ZZ5566", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(175), feedSources = Set(ApiFeedSource, LiveFeedSource))

      val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flight1, flight2, flight3))

      val expected = List(
        (flight1, Set()),
        (flight2, Set()),
        (flight3, Set())
      )

      result mustEqual expected
    }

    "Given three similar flights one with API data " +
      "When we ask for unique flights " +
      "Then we should see a tuple of only one flight with two code shares" in {
      val flight1: Arrival = apiFlight(flightId = Option(1), iata = "BA0001", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(100), feedSources = Set(ApiFeedSource, LiveFeedSource))
      val flight2: Arrival = apiFlight(flightId = Option(2), iata = "AA8778", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(150), feedSources = Set(LiveFeedSource))
      val flight3: Arrival = apiFlight(flightId = Option(3), iata = "ZZ5566", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(175), feedSources = Set(LiveFeedSource))

      val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flight1, flight2, flight3))

      val expected = List(
        (flight1, Set(flight2, flight3))
      )
      result mustEqual expected
    }

    "Given three similar flights two with API data " +
      "When we ask for unique flights " +
      "Then we should see a tuple of only one flight with one code shares and another with no code share" in {
      val flight1: Arrival = apiFlight(flightId = Option(1), iata = "BA0001", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(100), feedSources = Set(ApiFeedSource, LiveFeedSource))
      val flight2: Arrival = apiFlight(flightId = Option(2), iata = "AA8778", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(150), feedSources = Set(LiveFeedSource))
      val flight3: Arrival = apiFlight(flightId = Option(3), iata = "ZZ5566", schDt = "2016-01-01T10:25Z", terminal = "T1", origin = "JFK", actPax = Option(175), feedSources = Set(ApiFeedSource, LiveFeedSource))

      val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flight1, flight2, flight3))

      val expected = List(
        (flight1, Set(flight2)),
        (flight3, Set())
      )
      result mustEqual expected
    }

  }
}
