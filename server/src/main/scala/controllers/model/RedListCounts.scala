package controllers.model

import drt.shared.{PortCode, SDateLike}
import passengersplits.parsing.VoyageManifestParser.FlightPassengerInfoProtocol.PortCodeJsonFormat
import services.SDate
import spray.json.{DefaultJsonProtocol, JsNumber, JsValue, RootJsonFormat}

case class RedListCount(flightCode: String, portCode: PortCode, scheduled: SDateLike, paxCount: Int)

case class RedListCounts(counts: Iterable[RedListCount])

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
