package controllers.model

import actors.persistent.nebo.NeboArrivalActor
import actors.persistent.staffing.GetState
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.DataUpdates.FlightUpdates
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import drt.shared.{RedListPassengers, _}
import services.SDate
import spray.json.{DefaultJsonProtocol, JsArray, JsNumber, JsString, JsValue, JsonFormat, RootJsonFormat, enrichAny}
import uk.gov.homeoffice.drt.ports.PortCode

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}
import scala.concurrent.duration._


case class RedListCounts(counts: Iterable[RedListPassengers]) extends FlightUpdates {

  def diffWith(state: FlightsApi.FlightsWithSplits, now: MillisSinceEpoch)(implicit system: ActorSystem, timeout: Timeout): FlightsWithSplitsDiff = {
    counts.foldLeft(FlightsWithSplitsDiff.empty) {
      case (diff, RedListPassengers(flightCode, _, scheduled, urns)) =>
        val (_, voyageNumber, _) = FlightCode.flightCodeToParts(flightCode)
        state.flights.values.find(matchesScheduledAndVoyageNumber(_, scheduled, voyageNumber)) match {
          case None => diff
          case Some(fws) =>
            val updatedArrival = fws.apiFlight.copy(RedListPax = Option(urns.size))

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

  implicit object PortCodeFormat extends RootJsonFormat[PortCode] {
    override def write(obj: PortCode): JsValue = JsString(obj.iata)

    override def read(json: JsValue): PortCode = json match {
      case JsString(value) => PortCode(value)
      case unexpected => throw new Exception(s"Failed to parse String. Expected String. Got ${unexpected.getClass}")
    }
  }

  implicit val redListCountFormat: RootJsonFormat[RedListPassengers] = jsonFormat4(RedListPassengers.apply)


  implicit object redListCountsFormat extends RootJsonFormat[RedListCounts] {
    override def write(obj: RedListCounts): JsValue = obj.counts.toJson

    override def read(json: JsValue): RedListCounts = json match {
      case JsArray(elements) =>
        RedListCounts(elements
          .map(count => Try(count.convertTo[RedListPassengers]))
          .collect { case Success(rlc) => rlc })
    }
  }
}
