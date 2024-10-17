package services.api.v1.serialisation

import services.api.v1.FlightExport.{FlightJson, PortFlightsJson, TerminalFlightsJson}
import spray.json.{DefaultJsonProtocol, JsObject, JsString, JsValue, RootJsonFormat, enrichAny}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDate

trait FlightApiJsonProtocol extends DefaultJsonProtocol {
  implicit object FlightJsonJsonFormat extends RootJsonFormat[FlightJson] {
    override def write(obj: FlightJson): JsValue = JsObject(
      "code" -> obj.code.toJson,
      "originPort" -> obj.originPort.toJson,
      "scheduledTime" -> SDate(obj.scheduledTime).toISOString.toJson,
      "estimatedPcpStartTime" -> obj.estimatedPcpStartTime.map(SDate(_).toISOString).toJson,
      "estimatedPcpEndTime" -> obj.estimatedPcpEndTime.map(SDate(_).toISOString).toJson,
      "estimatedPaxCount" -> obj.estimatedPaxCount.toJson,
      "status" -> obj.status.toJson
    )

    override def read(json: JsValue): FlightJson = json match {
      case JsObject(fields) => FlightJson(
        fields.get("code").map(_.convertTo[String]).getOrElse(""),
        fields.get("originPort").map(_.convertTo[String]).getOrElse(""),
        fields.get("scheduledTime").map(_.convertTo[Long]).getOrElse(0L),
        fields.get("estimatedPcpStartTime").map(_.convertTo[Long]),
        fields.get("estimatedPcpEndTime").map(_.convertTo[Long]),
        fields.get("estimatedPaxCount").map(_.convertTo[Int]),
        fields.get("status").map(_.convertTo[String]).getOrElse(""),
      )
      case unexpected => throw new Exception(s"Failed to parse FlightJson. Expected JsString. Got ${unexpected.getClass}")
    }
  }

  implicit val flightJsonFormat: RootJsonFormat[FlightJson] = jsonFormat7(FlightJson.apply)

  implicit object TerminalJsonFormat extends RootJsonFormat[Terminal] {
    override def write(obj: Terminal): JsValue = obj.toString.toJson

    override def read(json: JsValue): Terminal = json match {
      case JsString(value) => Terminal(value)
      case unexpected => throw new Exception(s"Failed to parse Terminal. Expected JsString. Got ${unexpected.getClass}")
    }
  }

  implicit val terminalFlightsJsonFormat: RootJsonFormat[TerminalFlightsJson] = jsonFormat2(TerminalFlightsJson.apply)

  implicit object PortCodeJsonFormat extends RootJsonFormat[PortCode] {
    override def write(obj: PortCode): JsValue = obj.iata.toJson

    override def read(json: JsValue): PortCode = json match {
      case JsString(value) => PortCode(value)
      case unexpected => throw new Exception(s"Failed to parse Terminal. Expected JsString. Got ${unexpected.getClass}")
    }
  }


  implicit val portFlightsJsonFormat: RootJsonFormat[PortFlightsJson] = jsonFormat2(PortFlightsJson.apply)
}
