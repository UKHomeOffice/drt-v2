package services.arrivals

import drt.shared._
import org.specs2.mutable.Specification

case class ArrivalSourcesMerger(arrivalDataSanitiser: ArrivalDataSanitiser, pcpArrivalTime: Arrival => MilliDate) {
  def merge(maybeLiveBase: Option[Arrival],
            maybeLive: Option[Arrival],
            maybeForecastBase: Option[Arrival],
            maybeForecast: Option[Arrival]) = {

  }
}

case class ArrivalsMerger(liveBaseArrivals: Map[UniqueArrival, Arrival],
                          liveArrivals: Map[UniqueArrival, Arrival],
                          forecastBaseArrivals: Map[UniqueArrival, Arrival],
                          forecastArrivals: Map[UniqueArrival, Arrival],
                          merged: Map[UniqueArrival, Arrival],
                          arrivalDataSanitiser: ArrivalDataSanitiser,
                          pcpArrivalTime: Arrival => MilliDate) {

  def getUpdatesFromNonBaseArrivals(keys: Iterable[UniqueArrival]): Map[UniqueArrival, Arrival] = keys
    .foldLeft(Map[UniqueArrival, Arrival]()) {
      case (updatedArrivalsSoFar, key) =>
        mergeArrival(key) match {
          case Some(mergedArrival) =>
            if (arrivalHasUpdates(merged.get(key), mergedArrival)) updatedArrivalsSoFar.updated(key, mergedArrival) else updatedArrivalsSoFar
          case None => updatedArrivalsSoFar
        }
    }

  def arrivalHasUpdates(maybeExistingArrival: Option[Arrival], updatedArrival: Arrival): Boolean = {
    maybeExistingArrival.isEmpty || !maybeExistingArrival.get.equals(updatedArrival)
  }

  def mergeArrival(key: UniqueArrival): Option[Arrival] = {
    val maybeLiveBaseArrivalWithSanitisedData = liveBaseArrivals.get(key).map(arrivalDataSanitiser.withSaneEstimates)
    val maybeBestArrival: Option[Arrival] = (
      liveArrivals.get(key),
      maybeLiveBaseArrivalWithSanitisedData) match {
      case (Some(liveArrival), None) => Option(liveArrival)
      case (Some(liveArrival), Some(baseLiveArrival)) =>
        val mergedLiveArrival = LiveArrivalsUtil.mergePortFeedWithBase(liveArrival, baseLiveArrival)
        val sanitisedLiveArrival = ArrivalDataSanitiser
          .arrivalDataSanitiserWithoutThresholds
          .withSaneEstimates(mergedLiveArrival)
        Option(sanitisedLiveArrival)
      case (None, Some(baseLiveArrival)) if forecastBaseArrivals.contains(key) =>

        Option(baseLiveArrival)
      case _ => forecastBaseArrivals.get(key)
    }
    maybeBestArrival.map(bestArrival => {
      val arrivalForFlightCode = forecastBaseArrivals.getOrElse(key, bestArrival)
      mergeBestFieldsFromSources(arrivalForFlightCode, bestArrival)
    })
  }

  def mergeBestFieldsFromSources(baseArrival: Arrival, bestArrival: Arrival): Arrival = {
    val key = UniqueArrival(baseArrival)
    val (pax, transPax) = bestPaxNos(key)
    bestArrival.copy(
      CarrierCode = baseArrival.CarrierCode,
      VoyageNumber = baseArrival.VoyageNumber,
      ActPax = pax,
      TranPax = transPax,
      Status = bestStatus(key),
      FeedSources = feedSources(key),
      PcpTime = Option(pcpArrivalTime(bestArrival).millisSinceEpoch)
      )
  }

  def bestPaxNos(key: UniqueArrival): (Option[Int], Option[Int]) = (liveArrivals.get(key), forecastArrivals.get(key), forecastBaseArrivals.get(key)) match {
    case (Some(liveArrival), _, _) if liveArrival.ActPax.exists(_ > 0) => (liveArrival.ActPax, liveArrival.TranPax)
    case (_, Some(fcstArrival), _) if fcstArrival.ActPax.exists(_ > 0) => (fcstArrival.ActPax, fcstArrival.TranPax)
    case (_, _, Some(baseArrival)) if baseArrival.ActPax.exists(_ > 0) => (baseArrival.ActPax, baseArrival.TranPax)
    case _ => (None, None)
  }

  def feedSources(uniqueArrival: UniqueArrival): Set[FeedSource] = List(
    liveArrivals.get(uniqueArrival).map(_ => LiveFeedSource),
    forecastArrivals.get(uniqueArrival).map(_ => ForecastFeedSource),
    forecastBaseArrivals.get(uniqueArrival).map(_ => AclFeedSource),
    liveBaseArrivals.get(uniqueArrival).map(_ => LiveBaseFeedSource)
    ).flatten.toSet

  def bestStatus(key: UniqueArrival): ArrivalStatus =
    (liveArrivals.get(key), liveBaseArrivals.get(key), forecastArrivals.get(key), forecastBaseArrivals.get(key)) match {
      case (Some(live), Some(liveBase), _, _) if live.Status.description == "UNK" => liveBase.Status
      case (Some(live), _, _, _) => live.Status
      case (_, Some(liveBase), _, _) => liveBase.Status
      case (_, _, Some(forecast), _) => forecast.Status
      case (_, _, _, Some(forecastBase)) => forecastBase.Status
      case _ => ArrivalStatus("Unknown")
    }
}

class ArrivalMergeSpec extends Specification {

}
