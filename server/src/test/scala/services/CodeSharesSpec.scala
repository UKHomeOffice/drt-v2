package services

import controllers.ArrivalGenerator.live
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.EventTypes.DC
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Passengers, Splits}
import uk.gov.homeoffice.drt.ports.PaxTypes.GBRNational
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import uk.gov.homeoffice.drt.ports._


class CodeSharesSpec extends Specification {

  import drt.shared.CodeShares._

  val paxFeedSourceOrder: List[FeedSource] = List(LiveFeedSource, ApiFeedSource)

  "Given one flight " +
    "When we ask for unique arrivals " +
    "Then we should see that flight with zero code shares " >> {
    val flight = ApiFlightWithSplits(live(iata = "BA0001", schDt = "2016-01-01T10:25Z", totalPax = Option(100), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())

    val result = uniqueFlightsWithCodeShares(paxFeedSourceOrder)(Seq(flight))

    val expected = List((flight, Seq()))

    result === expected
  }

  private def aclPassengers(paxCount: Int): Map[FeedSource, Passengers] = {
    Map(AclFeedSource -> Passengers(Option(paxCount), None))
  }

  "Given two flights which are codeshares of each other " +
    "When we ask for unique flights " +
    "Then we should see a tuple of only one flight with its code share" >> {
    val flight1 = ApiFlightWithSplits(live(iata = "BA0001", schDt = "2016-01-01T10:25Z", totalPax = Option(100), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())
    val flight2 = ApiFlightWithSplits(live(iata = "AA8778", schDt = "2016-01-01T10:25Z", totalPax = Option(150), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())

    val result = uniqueFlightsWithCodeShares(paxFeedSourceOrder)(Seq(flight1, flight2))

    val expected = List((flight2, Seq(flight1.apiFlight.flightCodeString)))

    result === expected
  }

  "Given three flights which are codeshares of each other " +
    "When we ask for unique flights " +
    "Then we should see a tuple of only one flight with its two code shares" >> {
    val flight1 = ApiFlightWithSplits(live(iata = "BA0001", schDt = "2016-01-01T10:25Z", totalPax = Option(100), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())
    val flight2 = ApiFlightWithSplits(live(iata = "AA8778", schDt = "2016-01-01T10:25Z", totalPax = Option(150), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())
    val flight3 = ApiFlightWithSplits(live(iata = "ZZ5566", schDt = "2016-01-01T10:25Z", totalPax = Option(175), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())

    val result = uniqueFlightsWithCodeShares(paxFeedSourceOrder)(Seq(flight1, flight2, flight3))

    val expected = List((flight3, Seq(flight1.apiFlight.flightCodeString, flight2.apiFlight.flightCodeString)))

    result === expected
  }

  "Given 3 flight, where there is a set of code shares and one unique flight " +
    "When we ask for unique flights " +
    "Then we should see a 3 tuples; one flight with no code shares and 2 flights with their two code shares" >> {
    val flightCS1a = ApiFlightWithSplits(live(iata = "BA0001", schDt = "2016-01-01T10:25Z", totalPax = Option(100), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())
    val flightCS1b = ApiFlightWithSplits(live(iata = "AA8778", schDt = "2016-01-01T10:25Z", totalPax = Option(150), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())
    val flight = ApiFlightWithSplits(live(iata = "KL1010", schDt = "2016-01-01T10:25Z", totalPax = Option(175), terminal = T2, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())

    val result = uniqueFlightsWithCodeShares(paxFeedSourceOrder)(Seq(flightCS1a, flightCS1b, flight)).toSet

    val expected = Set(
      (flightCS1b, Seq(flightCS1a.apiFlight.flightCodeString)),
      (flight, Seq())
    )

    result === expected
  }

  "Given two flights with the same scheduled time, the same terminal, but different origins " +
    "When we ask for unique flights " +
    "Then we should see two tuples, each with one of the flights and no code shares" >> {
    val flight1 = ApiFlightWithSplits(live(iata = "BA0001", schDt = "2016-01-01T10:25Z", totalPax = Option(100), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())
    val flight2 = ApiFlightWithSplits(live(iata = "AA8778", schDt = "2016-01-01T10:25Z", totalPax = Option(150), terminal = T1, origin = PortCode("CDG")).toArrival(LiveFeedSource), Set())

    val result = uniqueFlightsWithCodeShares(paxFeedSourceOrder)(Seq(flight1, flight2)).toSet

    val expected = Set(
      (flight1, Seq()),
      (flight2, Seq())
    )

    result === expected
  }

  "Given two flights with the same scheduled time, the same origins, but different terminals " +
    "When we ask for unique flights " +
    "Then we should see two tuples, each with one of the flights and no code shares" >> {
    val flight1 = ApiFlightWithSplits(live(iata = "BA0001", schDt = "2016-01-01T10:25Z", totalPax = Option(100), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())
    val flight2 = ApiFlightWithSplits(live(iata = "AA8778", schDt = "2016-01-01T10:25Z", totalPax = Option(150), terminal = T2, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())

    val result = uniqueFlightsWithCodeShares(paxFeedSourceOrder)(Seq(flight1, flight2)).toSet

    val expected = Set(
      (flight1, Seq()),
      (flight2, Seq())
    )

    result === expected
  }

  "Given two flights with the same origins, the same different terminals, but different scheduled times" +
    "When we ask for unique flights " +
    "Then we should see two tuples, each with one of the flights and no code shares" >> {
    val flight1 = ApiFlightWithSplits(live(iata = "BA0001", schDt = "2016-01-01T10:30Z", totalPax = Option(100), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())
    val flight2 = ApiFlightWithSplits(live(iata = "AA8778", schDt = "2016-01-01T10:25Z", totalPax = Option(150), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())

    val result = uniqueFlightsWithCodeShares(paxFeedSourceOrder)(Seq(flight1, flight2)).toSet

    val expected = Set(
      (flight1, Seq()),
      (flight2, Seq())
    )

    result === expected
  }

  "Given two code-share flights where one has a higher number of passengers but no live API data, and the other does has live API data" should {
    "The main flight should be the one with the live API data" in {
      val apiSplits = Splits(Set(ApiPaxTypeAndQueueCount(GBRNational, EeaDesk, 50, None, None)), ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DC))

      val flight1 = ApiFlightWithSplits(live(iata = "BA0001", schDt = "2016-01-01T10:30Z", totalPax = Option(100), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set(apiSplits))
      val flight2 = ApiFlightWithSplits(live(iata = "AA8778", schDt = "2016-01-01T10:30Z", totalPax = Option(150), terminal = T1, origin = PortCode("JFK")).toArrival(LiveFeedSource), Set())

      val result = uniqueFlightsWithCodeShares(paxFeedSourceOrder)(Seq(flight1, flight2)).toSet

      val expected = Set(
        (flight1, Seq("AA8778")),
      )

      result === expected
    }
  }
}
