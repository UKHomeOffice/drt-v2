package services

import controllers.ArrivalGenerator.arrival
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers}
import uk.gov.homeoffice.drt.ports.{AclFeedSource, PortCode}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}


class CodeSharesSpec extends Specification {

  import drt.shared.CodeShares._

  "Given one flight " +
    "When we ask for unique arrivals " +
    "Then we should see that flight with zero code shares " >> {
    val flight: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(100), None)), terminal = T1, origin = PortCode("JFK"))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flight))

    val expected = List((flight, Set()))

    result === expected
  }

  "Given two flights which are codeshares of each other " +
    "When we ask for unique flights " +
    "Then we should see a tuple of only one flight with its code share" >> {
    val flight1: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(100), None)), terminal = T1, origin = PortCode("JFK"))
    val flight2: Arrival = arrival(iata = "AA8778", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(150), None)), terminal = T1, origin = PortCode("JFK"))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flight1, flight2))

    val expected = List((flight2, Set(flight1)))

    result === expected
  }

  "Given three flights which are codeshares of each other " +
    "When we ask for unique flights " +
    "Then we should see a tuple of only one flight with its two code shares" >> {
    val flight1: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(100), None)), terminal = T1, origin = PortCode("JFK"))
    val flight2: Arrival = arrival(iata = "AA8778", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(150), None)), terminal = T1, origin = PortCode("JFK"))
    val flight3: Arrival = arrival(iata = "ZZ5566", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(175), None)), terminal = T1, origin = PortCode("JFK"))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flight1, flight2, flight3))

    val expected = List((flight3, Set(flight1, flight2)))

    result === expected
  }

  "Given 5 flight, where there are 2 sets of code shares and one unique flight " +
    "When we ask for unique flights " +
    "Then we should see a 3 tuples; one flight with no code shares and 2 flights with their two code shares" >> {
    val flightCS1a: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(100), None)), terminal = T1, origin = PortCode("JFK"))
    val flightCS1b: Arrival = arrival(iata = "AA8778", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(150), None)), terminal = T1, origin = PortCode("JFK"))
    val flightCS2a: Arrival = arrival(iata = "ZZ5566", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(55), None)), terminal = T1, origin = PortCode("CDG"))
    val flightCS2b: Arrival = arrival(iata = "TG8000", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(180), None)), terminal = T1, origin = PortCode("CDG"))
    val flight: Arrival = arrival(iata = "KL1010", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(175), None)), terminal = T2, origin = PortCode("JFK"))

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
    val flight1: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(100), None)), terminal = T1, origin = PortCode("JFK"))
    val flight2: Arrival = arrival(iata = "AA8778", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(150), None)), terminal = T1, origin = PortCode("CDG"))

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
    val flight1: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(100), None)), terminal = T1, origin = PortCode("JFK"))
    val flight2: Arrival = arrival(iata = "AA8778", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(150), None)), terminal = T2, origin = PortCode("JFK"))

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
    val flight1: Arrival = arrival(iata = "BA0001", schDt = "2016-01-01T10:30Z", totalPax = Map(AclFeedSource -> Passengers(Option(100), None)), terminal = T1, origin = PortCode("JFK"))
    val flight2: Arrival = arrival(iata = "AA8778", schDt = "2016-01-01T10:25Z", totalPax = Map(AclFeedSource -> Passengers(Option(150), None)), terminal = T1, origin = PortCode("JFK"))

    val result = uniqueArrivalsWithCodeShares(identity[Arrival])(Seq(flight1, flight2)).toSet

    val expected = Set(
      (flight1, Set()),
      (flight2, Set())
    )

    result === expected
  }
}
