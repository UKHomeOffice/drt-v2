package services.graphstages

import drt.shared.api.Arrival
import drt.shared.{MilliTimes, PortCode, UniqueArrival}
import org.slf4j.{Logger, LoggerFactory}
import services.arrivals.LiveArrivalsUtil

import scala.collection.immutable.SortedMap


object ApproximateScheduleMatch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def find(uniqueArrival: UniqueArrival, maybeOrigin: Option[PortCode], arrivalsToSearch: Map[UniqueArrival, Arrival], windowMillis: Int): Either[Arrival, List[Arrival]] = {
    val approxMatches = arrivalsToSearch
      .filterKeys(_.equalWithinScheduledWindow(uniqueArrival, windowMillis))
      .filter {
        case (_, potentialArrival) =>
          maybeOrigin match {
            case None => true
            case Some(origin) => potentialArrival.Origin == origin
          }
      }

    approxMatches.values.toList match {
      case singleMatch :: Nil => Left(singleMatch)
      case nonSingleMatch => Right(nonSingleMatch)
    }
  }

  def mergeApproxIfFoundElseNone(arrival: Arrival,
                                 maybeOrigin: Option[PortCode],
                                 sourcesToSearch: List[(ArrivalsSourceType, Map[UniqueArrival, Arrival])]): Option[Arrival] =
    sourcesToSearch.foldLeft[Option[Arrival]](None) {
      case (Some(found), _) => Some(found)
      case (None, (sourceToSearch, arrivalsForSource)) => ApproximateScheduleMatch.maybeMergeApproxMatch(arrival, maybeOrigin, sourceToSearch, arrivalsForSource)
    }

  def mergeApproxIfFoundElseOriginal(arrival: Arrival,
                                     maybeOrigin: Option[PortCode],
                                     sourcesToSearch: List[(ArrivalsSourceType, Map[UniqueArrival, Arrival])]): Option[Arrival] =
    mergeApproxIfFoundElseNone(arrival, maybeOrigin, sourcesToSearch) match {
      case Some(found) => Some(found)
      case None => Some(arrival)
    }

  def maybeMergeApproxMatch(arrival: Arrival,
                            maybeOrigin: Option[PortCode],
                            searchSource: ArrivalsSourceType,
                            arrivalsToSearch: Map[UniqueArrival, Arrival]): Option[Arrival] = {
    val key = arrival.unique
    find(key, maybeOrigin, arrivalsToSearch, MilliTimes.oneHourMillis) match {
      case Left(matchedArrival) =>
        if (searchSource == LiveBaseArrivals)
          Option(LiveArrivalsUtil.mergePortFeedWithLiveBase(arrival, matchedArrival))
        else
          Option(LiveArrivalsUtil.mergePortFeedWithLiveBase(matchedArrival, arrival))
      case Right(none) if none.isEmpty =>
        if (searchSource == LiveBaseArrivals) log.warn(s"No cirium matches found for arrival $key")
        None
      case Right(_) =>
        if (searchSource == LiveBaseArrivals) log.warn(s"Multiple cirium matches found for arrival $key")
        None
    }
  }
}
