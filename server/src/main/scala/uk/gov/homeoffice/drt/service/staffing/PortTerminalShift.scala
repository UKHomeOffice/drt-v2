package uk.gov.homeoffice.drt.service.staffing

import uk.gov.homeoffice.drt.db.tables.PortTerminalShiftConfig
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import upickle.default.{readwriter, write}
import upickle.default._

case class PortTerminalShift(port: String,
                             terminal: String,
                             shiftName: String,
                             startAt: Long,
                             periodInMinutes: Int,
                             endAt: Option[Long],
                             frequency: Option[String],
                             actualStaff: Option[Int],
                             minimumRosteredStaff: Option[Int],
                             email: String
                            )

trait PortTerminalShiftI {
  def extractOptional(json: ujson.Value, key: String, extractor: ujson.Value => Any): Option[Any] = {
    json.obj.get(key).flatMap {
      case value if value != ujson.Null => Some(extractor(value))
      case _ => None
    }
  }
}

object PortTerminalShiftConfigJsonSerializer extends PortTerminalShiftI {
  def writeToJson(shift: PortTerminalShiftConfig): String = write(shift)

  implicit val portTerminalShiftConfigRW: ReadWriter[PortTerminalShiftConfig] = {
    readwriter[ujson.Value].bimap[PortTerminalShiftConfig](
      config => ujson.Obj(
        "port" -> config.port.toString,
        "terminal" -> config.terminal.toString,
        "shiftName" -> config.shiftName,
        "startAt" -> config.startAt,
        "periodInMinutes" -> config.periodInMinutes,
        "endAt" -> config.endAt.map(ujson.Num(_)).getOrElse(ujson.Null),
        "frequency" -> config.frequency.map(ujson.Str(_)).getOrElse(ujson.Null),
        "actualStaff" -> config.actualStaff.map(ujson.Num(_)).getOrElse(ujson.Null),
        "minimumRosteredStaff" -> config.minimumRosteredStaff.map(ujson.Num(_)).getOrElse(ujson.Null),
        "updatedAt" -> config.updatedAt,
        "email" -> config.email
      ),
      json => PortTerminalShiftConfig(
        port = PortCode(json("port").str),
        terminal = Terminal(json("terminal").str),
        shiftName = json("shiftName").str,
        startAt = json("startAt").num.toLong,
        periodInMinutes = json("periodInMinutes").num.toInt,
        endAt = extractOptional(json, "endAt", _.num.toLong).asInstanceOf[Option[Long]],
        frequency = extractOptional(json, "frequency", _.str).asInstanceOf[Option[String]],
        actualStaff = extractOptional(json, "actualStaff", _.num.toInt).asInstanceOf[Option[Int]],
        minimumRosteredStaff = extractOptional(json, "minimumRosteredStaff", _.num.toInt).asInstanceOf[Option[Int]],
        updatedAt = json("updatedAt").num.toLong,
        email = json("email").str
      )
    )
  }
}

object PortTerminalShiftJsonSerializer extends PortTerminalShiftI {
  implicit val portTerminalShiftRW: ReadWriter[PortTerminalShift] = {
    readwriter[ujson.Value].bimap[PortTerminalShift](
      shift => ujson.Obj(
        "port" -> shift.port,
        "terminal" -> shift.terminal,
        "shiftName" -> shift.shiftName,
        "startAt" -> shift.startAt,
        "periodInMinutes" -> shift.periodInMinutes,
        "endAt" -> shift.endAt.map(ujson.Num(_)).getOrElse(ujson.Null),
        "frequency" -> shift.frequency.map(ujson.Str(_)).getOrElse(ujson.Null),
        "actualStaff" -> shift.actualStaff.map(ujson.Num(_)).getOrElse(ujson.Null),
        "minimumRosteredStaff" -> shift.minimumRosteredStaff.map(ujson.Num(_)).getOrElse(ujson.Null),
        "email" -> shift.email
      ),
      json => PortTerminalShift(
        port = json("port").str,
        terminal = json("terminal").str,
        shiftName = json("shiftName").str,
        startAt = json("startAt").num.toLong,
        periodInMinutes = json("periodInMinutes").num.toInt,
        endAt = extractOptional(json, "endAt", _.num.toLong).asInstanceOf[Option[Long]],
        frequency = extractOptional(json, "frequency", _.str).asInstanceOf[Option[String]],
        actualStaff = extractOptional(json, "actualStaff", _.num.toInt).asInstanceOf[Option[Int]],
        minimumRosteredStaff = extractOptional(json, "minimumRosteredStaff", _.num.toInt).asInstanceOf[Option[Int]],
        email = json("email").str
      )
    )
  }
}
