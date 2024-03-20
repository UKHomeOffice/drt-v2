package services

import controllers.ArrivalGenerator
import drt.shared.CrunchApi._
import drt.shared._
import org.specs2.mutable.Specification
import services.crunch.TestDefaults
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits, FlightsWithSplitsDiff}
import uk.gov.homeoffice.drt.ports.{FeedSource, LiveFeedSource}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate

class PortStateMinutesSpec extends Specification {
  val now: MillisSinceEpoch = SDate.now().millisSinceEpoch

  val paxFeedSourceOrder: List[FeedSource] = TestDefaults.paxFeedSourceOrder

  "When I apply a FlightsWithSplits " >> {
    "Containing only new arrivals " >> {
      val newFlightsWithSplits = FlightsWithSplitsDiff(
        (1 to 5).map(d => ApiFlightWithSplits(
          ArrivalGenerator.arrival(iata = "BA0001", schDt = s"2019-01-0${d}T12:00.00Z", terminal = T1).toArrival(LiveFeedSource), Set())
        ).toList, List())

      "To an empty PortState" >> {
        "Then I should see those flights in the PortState" >> {
          val (portState, _) = newFlightsWithSplits.applyTo(FlightsWithSplits.empty, now, paxFeedSourceOrder)
          val expected = PortState(newFlightsWithSplits.flightsToUpdate.map(_.copy(lastUpdated = Option(now))), List(), List())

          portState.flights === expected.flights
        }
      }
    }
  }
}
