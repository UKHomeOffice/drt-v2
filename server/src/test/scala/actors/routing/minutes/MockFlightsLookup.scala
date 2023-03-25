package actors.routing.minutes

import actors.routing.minutes.MinutesActorLike.FlightsLookup
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.{ExecutionContextExecutor, Future}

case class MockFlightsLookup() {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  var paramsLookup: List[(Terminal, UtcDate, Option[MillisSinceEpoch])] = List()

  def lookup(mockData: FlightsWithSplits = FlightsWithSplits.empty): FlightsLookup = {
    val byDay: Map[UtcDate, Map[UniqueArrival, ApiFlightWithSplits]] = mockData.flights.groupBy {
      case (_, fws) => SDate(fws.apiFlight.Scheduled).toUtcDate
    }
    (pit: Option[MillisSinceEpoch]) => (d: UtcDate) => (t: Terminal) => {
      paramsLookup = paramsLookup :+ ((t, d, pit))
      Future(FlightsWithSplits(byDay.getOrElse(d, Map())))
    }
  }
}
