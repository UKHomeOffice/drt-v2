package controllers.model

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.DataUpdates.FlightUpdates
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.FlightPassengerInfoProtocol.PortCodeJsonFormat
import services.SDate
import spray.json.{DefaultJsonProtocol, JsNumber, JsValue, RootJsonFormat}

case class RedListCount(flightCode: String, portCode: PortCode, scheduled: SDateLike, paxCount: Int)

case class RedListCounts(counts: Iterable[RedListCount]) extends FlightUpdates {
  def diffWith(state: FlightsApi.FlightsWithSplits, now: MillisSinceEpoch): FlightsWithSplitsDiff = {
    counts.foldLeft(FlightsWithSplitsDiff.empty) {
      case (diff, RedListCount(flightCode, _, scheduled, count)) =>
        val (_, voyageNumber, _) = FlightCode.flightCodeToParts(flightCode)
        state.flights.values.find(matchesScheduledAndVoyageNumber(_, scheduled, voyageNumber)) match {
          case None => diff
          case Some(fws) =>
            val updatedArrival = fws.apiFlight.copy(RedListPax = Option(count))
            diff.copy(flightsToUpdate = diff.flightsToUpdate ++ Iterable(fws.copy(apiFlight = updatedArrival, lastUpdated = Option(now))))
        }
    }
  }

  private def matchesScheduledAndVoyageNumber(fws: ApiFlightWithSplits, scheduled: SDateLike, voyageNumber: VoyageNumberLike) = {
    fws.apiFlight.Scheduled == scheduled.millisSinceEpoch && fws.apiFlight.VoyageNumber.numeric == voyageNumber.numeric
  }
}

object RedListCountsJsonFormats {

  import DefaultJsonProtocol._

  implicit object SDateJsonFormat extends RootJsonFormat[SDateLike] {
    override def write(obj: SDateLike): JsValue = JsNumber(obj.millisSinceEpoch)

    override def read(json: JsValue): SDateLike = json match {
      case JsNumber(value) => SDate(value.toLong)
      case unexpected => throw new Exception(s"Failed to parse SDate. Expected JsNumber. Got ${unexpected.getClass}")
    }
  }

  implicit val redListCountFormat: RootJsonFormat[RedListCount] = jsonFormat4(RedListCount.apply)

  implicit val redListCountsFormat: RootJsonFormat[RedListCounts] = jsonFormat1(RedListCounts.apply)
}
