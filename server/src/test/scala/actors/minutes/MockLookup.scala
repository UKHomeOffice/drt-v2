package actors.minutes

import actors.minutes.MinutesActorLike.{FlightsInRangeLookup, FlightsLookup}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared.{ApiFlightWithSplits, UniqueArrival, UtcDate}
import services.SDate

import scala.concurrent.{ExecutionContextExecutor, Future}

object MockLookup {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  var paramsLookup: List[(Terminal, UtcDate, Option[MillisSinceEpoch])] = List()
  var paramsLookupInRange: List[(Terminal, UtcDate, UtcDate, Option[MillisSinceEpoch])] = List()

  def reset(): Unit = {
    paramsLookup = List()
    paramsLookupInRange = List()
  }

  def lookup(mockData: FlightsWithSplits = FlightsWithSplits.empty): FlightsLookup = {
    reset()
    val byDay: Map[UtcDate, Map[UniqueArrival, ApiFlightWithSplits]] = mockData.flights.groupBy {
      case (_, fws) => SDate(fws.apiFlight.Scheduled).toUtcDate
    }
    (t: Terminal, d: UtcDate, pit: Option[MillisSinceEpoch]) => {
      paramsLookup = paramsLookup :+ (t, d, pit)

      Future(FlightsWithSplits(byDay.getOrElse(d, Map())))
    }
  }

  def lookupRange(mockData: FlightsWithSplits = FlightsWithSplits.empty): FlightsInRangeLookup = {
    (t: Terminal, start: UtcDate, end: UtcDate, pit: Option[MillisSinceEpoch]) => {
      paramsLookupInRange = paramsLookupInRange :+ (t, start, end, pit)

      Future(mockData)
    }
  }
}
