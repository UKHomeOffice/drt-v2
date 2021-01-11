package services.graphstages

import drt.shared.api.Arrival
import drt.shared.{PortCode, UniqueArrival}
import org.slf4j.{Logger, LoggerFactory}
import services.arrivals.LiveArrivalsUtil

import scala.collection.immutable.SortedMap

class ScheduledApproximation(searchKey: UniqueArrival,
                             forecastBaseArrivals: SortedMap[UniqueArrival, Arrival],
                             forecastArrivals: SortedMap[UniqueArrival, Arrival],
                             liveBaseArrivals: SortedMap[UniqueArrival, Arrival],
                             liveArrivals: SortedMap[UniqueArrival, Arrival]) {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  private[this] var potentialArrival: Option[Arrival] = None

  def findArrival(searchArrivalSourceType: ArrivalsSourceType, origin: PortCode, arrivalSourceType: ArrivalsSourceType): Boolean = {
    potentialArrival = None
    val searchArrivalMap: SortedMap[UniqueArrival, Arrival] = arrivalMapBySourceType(arrivalSourceType)
    val potentialKeys = getPotentialKeys(searchArrivalMap)

    potentialArrival = {
      val pKeyWithOriginFiltered: Iterable[Arrival] = getPotentialKeyWithOriginFiltered(potentialKeys, searchArrivalMap, origin)
      val keys: Seq[UniqueArrival] = pKeyWithOriginFiltered.toSeq.map(uniqueArrivalKeys)
      (potentialKeys.size, pKeyWithOriginFiltered.size) match {
        case (a, 1) if a >= 1 =>
          log.info(foundMessage("Found a", keys, searchArrivalSourceType, origin, arrivalSourceType))
          pKeyWithOriginFiltered.toSeq.headOption
        case (_, b) if b > 1 =>
          log.warn(foundMessage("Found multiple", keys, searchArrivalSourceType, origin, arrivalSourceType))
          pKeyWithOriginFiltered.toSeq.sortBy(_.Scheduled).headOption
        case (1, 0) =>
          log.warn(notFoundMessage(s"Not found origin $origin", searchArrivalSourceType, origin, arrivalSourceType))
          None
        case (0, 0) =>
          log.warn(notFoundMessage(s"Not found", searchArrivalSourceType, origin, arrivalSourceType))
          None
      }
    }

    potentialArrival.isDefined
  }

  def mergeArrival(searchArrivalSourceType: ArrivalsSourceType, existingFoundArrival: Arrival, arrivalSourceType: ArrivalsSourceType): Option[Arrival] = potentialArrival.map { aArrival =>

    log.info(s"Merged arrival with key ${uniqueArrivalKeys(aArrival)} for searchKey $searchKey and searchArrivalSourceType $searchArrivalSourceType and arrivalSourceType $arrivalSourceType")

    if (searchArrivalSourceType == LiveArrivals && arrivalSourceType == LiveBaseArrivals)
      LiveArrivalsUtil.mergePortFeedWithBase(existingFoundArrival, aArrival)
    else
      LiveArrivalsUtil.mergePortFeedWithBase(aArrival, existingFoundArrival)
  }

  private def uniqueArrivalKeys(arrival: Arrival): UniqueArrival = UniqueArrival(arrival.VoyageNumber.numeric, arrival.Terminal, arrival.Scheduled)

  private def getPotentialKeys(searchArrivalMap: SortedMap[UniqueArrival, Arrival]): Iterable[UniqueArrival] =
    searchArrivalMap.keys.filter(_.potentialKey(searchKey, 1 * 60 * 60 * 1000))


  private def getPotentialKeyWithOriginFiltered(potentialKeys: Iterable[UniqueArrival], searchArrivalMap: SortedMap[UniqueArrival, Arrival], origin: PortCode) =
    potentialKeys.flatMap(searchArrivalMap.get(_).filter(_.Origin == origin))

  private def arrivalMapBySourceType(searchSourceType: ArrivalsSourceType): SortedMap[UniqueArrival, Arrival] = searchSourceType match {
    case LiveArrivals => liveArrivals
    case LiveBaseArrivals => liveBaseArrivals
    case BaseArrivals => forecastBaseArrivals
  }


  private def foundMessage(message: String, keys: Seq[UniqueArrival], searchArrivalSourceType: ArrivalsSourceType, origin: PortCode, arrivalSourceType: ArrivalsSourceType) = {
    s"$message potentialKey $keys for searchArrivalSourceType $searchArrivalSourceType searchKey $searchKey within an hour of scheduled Approximation in sourceType $arrivalSourceType"
  }

  private def notFoundMessage(message: String, searchArrivalSourceType: ArrivalsSourceType, origin: PortCode, arrivalSourceType: ArrivalsSourceType) = {
    s"$message potentialKey for searchArrivalSourceType $searchArrivalSourceType searchKey $searchKey within an hour of scheduled Approximation in sourceType $arrivalSourceType"
  }

}