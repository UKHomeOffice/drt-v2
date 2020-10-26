package actors.minutes

import actors.minutes.MinutesActorLike.FlightsLookup
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared.{ApiFlightWithSplits, UniqueArrival, UtcDate}
import services.SDate

import scala.concurrent.{ExecutionContextExecutor, Future}

case class MockLookup() {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  var paramsLookup: List[(Terminal, UtcDate, Option[MillisSinceEpoch])] = List()
  var paramsLegacy1Lookup: List[(Terminal, UtcDate, Option[MillisSinceEpoch])] = List()
  var paramsLegacy2Lookup: List[(Terminal, UtcDate, Option[MillisSinceEpoch])] = List()

  def lookup(mockData: FlightsWithSplits = FlightsWithSplits.empty): FlightsLookup = {

    val byDay: Map[UtcDate, Map[UniqueArrival, ApiFlightWithSplits]] = mockData.flights.groupBy {
      case (_, fws) => SDate(fws.apiFlight.Scheduled).toUtcDate
    }
    (t: Terminal, d: UtcDate, pit: Option[MillisSinceEpoch]) => {
      paramsLookup = paramsLookup :+ (t, d, pit)
      Future(FlightsWithSplits(byDay.getOrElse(d, Map())))
    }
  }

  def legacy1Lookup(mockData: FlightsWithSplits = FlightsWithSplits.empty): FlightsLookup = {
    val byDay: Map[UtcDate, Map[UniqueArrival, ApiFlightWithSplits]] = mockData.flights.groupBy {
      case (_, fws) => SDate(fws.apiFlight.Scheduled).toUtcDate
    }

    (t: Terminal, d: UtcDate, pit: Option[MillisSinceEpoch]) => {
      paramsLegacy1Lookup = paramsLegacy1Lookup :+ (t, d, pit)
      Future(FlightsWithSplits(byDay.getOrElse(d, Map())))
    }
  }

  def legacy2Lookup(mockData: FlightsWithSplits = FlightsWithSplits.empty): FlightsLookup = {

    val byDay: Map[UtcDate, Map[UniqueArrival, ApiFlightWithSplits]] = mockData.flights.groupBy {
      case (_, fws) => SDate(fws.apiFlight.Scheduled).toUtcDate
    }

    (t: Terminal, d: UtcDate, pit: Option[MillisSinceEpoch]) => {
      paramsLegacy2Lookup = paramsLegacy2Lookup :+ (t, d, pit)
      Future(FlightsWithSplits(byDay.getOrElse(d, Map())))
    }
  }
}
