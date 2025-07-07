package controllers

import drt.shared.Shift
import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default.{macroRW, ReadWriter => RW, _}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ShiftsParsingSpec extends AnyFlatSpec with Matchers {
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
      "createdAt" -> shift.createdAt
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

  "upickle" should "parse JSON into Seq[Shift] correctly" in {
    val json = """
      [
        {
          "createdAt": 1751379140536,
          "createdBy": null,
          "endDate": null,
          "endTime": "21:00",
          "frequency": null,
          "port": "BHX",
          "shiftName": "Afternoon",
          "staffNumber": 4,
          "startDate": { "day": 1, "month": 6, "year": 2025 },
          "startTime": "11:30",
          "terminal": "T1"
        },
        {
          "createdAt": 1751383694928,
          "createdBy": null,
          "endDate": { "day": 1, "month": 12, "year": 2025 },
          "endTime": "15:30",
          "frequency": null,
          "port": "BHX",
          "shiftName": "Morning",
          "staffNumber": 8,
          "startDate": { "day": 1, "month": 6, "year": 2025 },
          "startTime": "05:30",
          "terminal": "T1"
        },
        {
          "createdAt": 1751379174677,
          "createdBy": null,
          "endDate": null,
          "endTime": "04:30",
          "frequency": null,
          "port": "BHX",
          "shiftName": "Late",
          "staffNumber": 3,
          "startDate": { "day": 1, "month": 6, "year": 2025 },
          "startTime": "20:00",
          "terminal": "T1"
        }
      ]
      """.stripMargin
    println(ujson.read(json).getClass)

    val shifts = read[Seq[Shift]](ujson.read(json))

    shifts.length shouldBe 3
    shifts.head.shiftName shouldBe "Afternoon"
    shifts(1).endDate shouldBe Some(LocalDate(2025, 12, 1))
    shifts(2).endDate shouldBe None
  }

  "upickle" should "write Seq[Shift] to JSON correctly" in {
    val shifts = Seq(
      Shift(
        port = "BHX",
        terminal = "T1",
        shiftName = "Afternoon",
        startDate = LocalDate(2025, 6, 1),
        startTime = "11:30",
        endTime = "21:00",
        endDate = None,
        staffNumber = 4,
        frequency = None,
        createdBy = None,
        createdAt = 1751379140536L
      ),
      Shift(
        port = "BHX",
        terminal = "T1",
        shiftName = "Morning",
        startDate = LocalDate(2025, 6, 1),
        startTime = "05:30",
        endTime = "15:30",
        endDate = Some(LocalDate(2025, 12, 1)),
        staffNumber = 8,
        frequency = None,
        createdBy = None,
        createdAt = 1751383694928L
      ),
      Shift(
        port = "BHX",
        terminal = "T1",
        shiftName = "Late",
        startDate = LocalDate(2025, 6, 1),
        startTime = "20:00",
        endTime = "04:30",
        endDate = None,
        staffNumber = 3,
        frequency = None,
        createdBy = None,
        createdAt = 1751379174677L
      )
    )
    val json = write(shifts)
    json should include ("Afternoon")
    json should include ("Morning")
    json should include ("Late")
    json should include ("\"endDate\":null")
    json should include ("\"endDate\":{\"day\":1,\"month\":12,\"year\":2025}")
  }


}
