package services.graphstages

import drt.shared.api.Arrival
import drt.shared.{PortCode, UniqueArrival}
import org.slf4j.{Logger, LoggerFactory}
import services.arrivals.LiveArrivalsUtil

import scala.collection.immutable.SortedMap


object ApproximateScheduleMatch {
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
}
