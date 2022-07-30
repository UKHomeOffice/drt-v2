package controllers.model


import drt.shared.DataUpdates.FlightUpdates
import drt.shared.{RedListPassengers, _}
import services.SDate
import spray.json.{DefaultJsonProtocol, JsArray, JsNumber, JsString, JsValue, RootJsonFormat, enrichAny}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.SDateLike

import scala.util.{Success, Try}

case class RedListCounts(passengers: Iterable[RedListPassengers]) extends FlightUpdates

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
    override def write(obj: RedListCounts): JsValue = obj.passengers.toJson

    override def read(json: JsValue): RedListCounts = json match {
      case JsArray(elements) =>
        RedListCounts(elements
          .map(count => Try(count.convertTo[RedListPassengers]))
          .collect { case Success(rlc) => rlc })
    }
  }
}
