package drt.shared

import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default.{ReadWriter => RW, read, readwriter, writeJs}

case class Shift(port: String,
                 terminal: String,
                 shiftName: String,
                 startDate: LocalDate,
                 startTime: String,
                 endTime: String,
                 endDate: Option[LocalDate],
                 staffNumber: Int,
                 frequency: Option[String],
                 createdBy: Option[String],
                 createdAt: Long)

object Shift {
  implicit val localDateRW: RW[LocalDate] =
    readwriter[ujson.Value].bimap[LocalDate](
      ld => ujson.Obj(
        "year" -> ld.year,
        "month" -> ld.month,
        "day" -> ld.day
      ),
      json => LocalDate(
        json("year").num.toInt,
        json("month").num.toInt,
        json("day").num.toInt
      )
    )

  implicit val shiftRW: RW[Shift] = readwriter[ujson.Value].bimap[Shift](
    shift => ujson.Obj(
      "port" -> shift.port,
      "terminal" -> shift.terminal,
      "shiftName" -> shift.shiftName,
      "startDate" -> writeJs(shift.startDate),
      "startTime" -> shift.startTime,
      "endTime" -> shift.endTime,
      "endDate" -> shift.endDate.map(writeJs(_)).getOrElse(ujson.Null),
      "staffNumber" -> shift.staffNumber,
      "frequency" -> shift.frequency.map(ujson.Str(_)).getOrElse(ujson.Null),
      "createdBy" -> shift.createdBy.map(ujson.Str(_)).getOrElse(ujson.Null),
      "createdAt" -> ujson.Num(shift.createdAt)
    ),
    json => {
      val obj = json.obj
      Shift(
        port = obj("port").str,
        terminal = obj("terminal").str,
        shiftName = obj("shiftName").str,
        startDate = read[LocalDate](obj("startDate")),
        startTime = obj("startTime").str,
        endTime = obj("endTime").str,
        endDate = obj.get("endDate").flatMap {
          case ujson.Null => None
          case other => Some(read[LocalDate](other))
        },
        staffNumber = obj("staffNumber").num.toInt,
        frequency = obj.get("frequency").collect { case ujson.Str(s) => s },
        createdBy = obj.get("createdBy").collect { case ujson.Str(s) => s },
        createdAt = obj("createdAt").num.toLong
      )
    }
  )
}