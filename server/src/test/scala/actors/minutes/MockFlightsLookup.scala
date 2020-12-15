package actors.minutes

import actors.minutes.MinutesActorLike.FlightsLookup
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared.dates.UtcDate
import drt.shared.{ApiFlightWithSplits, UniqueArrival}
import services.SDate

import scala.concurrent.{ExecutionContextExecutor, Future}

case class MockFlightsLookup() {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  var paramsLookup: List[(Terminal, UtcDate, Option[MillisSinceEpoch])] = List()

  def lookup(mockData: FlightsWithSplits = FlightsWithSplits.empty): FlightsLookup = {

    val byDay: Map[UtcDate, Map[UniqueArrival, ApiFlightWithSplits]] = mockData.flights.groupBy {
      case (_, fws) => SDate(fws.apiFlight.Scheduled).toUtcDate
    }
    (t: Terminal, d: UtcDate, pit: Option[MillisSinceEpoch]) => {
      paramsLookup = paramsLookup :+ (t, d, pit)
      Future(FlightsWithSplits(byDay.getOrElse(d, Map())))
    }
  }
}
