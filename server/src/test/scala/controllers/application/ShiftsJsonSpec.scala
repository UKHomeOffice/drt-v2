package controllers.application

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import uk.gov.homeoffice.drt.time.LocalDate
import drt.shared.Shift

class ShiftsJsonSpec extends AnyFlatSpec with Matchers with StaffShiftsJson {

  "LocalDate JSON format" should "serialize and deserialize correctly" in {
    val date = LocalDate(2023, 10, 25)
    val json = date.toJson
    json shouldBe JsObject("year" -> JsNumber(2023), "month" -> JsNumber(10), "day" -> JsNumber(25))
    json.convertTo[LocalDate] shouldBe date
  }

  "StaffShift JSON format" should "serialize and deserialize correctly" in {

    val shift = Shift(
      port = "BHX",
      terminal = "T1",
      shiftName = "shift1",
      startDate = LocalDate(2024, 12, 1),
      startTime = "03:30",
      endTime = "21:00",
      endDate = None,
      staffNumber = 1,
      frequency = None,
      createdBy = None,
      createdAt = 1L
    )
    val json = shift.toJson
    json.convertTo[Shift] shouldBe shift
  }

  "convertTo" should "convert JSON string to Seq[StaffShift]" in {
    val jsonString =
      """
        |[
        |  {
        |    "port": "BHX",
        |    "terminal": "T1",
        |    "shiftName": "shift1",
        |    "startDate": {
        |      "year": 2024,
        |      "month": 12,
        |      "day": 1
        |    },
        |    "startTime": "03:30",
        |    "endTime": "21:00",
        |    "endDate":[],
        |    "staffNumber": 0,
        |    "frequency": [],
        |    "createdBy": [],
        |    "createdAt": 1
        |  }
        |]
        |""".stripMargin

    val shifts = shiftsFromJson(jsonString)
    shifts should have size 1
    shifts.head.shiftName shouldBe "shift1"
  }

  "convertTo" should "handle string to Seq[StaffShift]" in {
    val jsonString =
      s"""
         |{"port":"BHX",
         |"terminal":"T1",
         |"shiftName":"Morning",
         |"startDate":{"year":2025,"month":7,"day":1},
         |"startTime":"05:30",
         |"endTime":"15:30",
         |"endDate":null,
         |"staffNumber":6,
         |"frequency":null,
         |"createdBy":null,
         |"createdAt":1
         |}
         |""".stripMargin
    val shifts = shiftFromJson(jsonString)
    shifts.shiftName shouldBe "Morning"
  }
}
