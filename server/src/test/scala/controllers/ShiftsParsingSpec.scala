package controllers

import drt.shared.Shift
import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import drt.shared.Shift._
class ShiftsParsingSpec extends AnyFlatSpec with Matchers {

  "upickle" should "parse JSON into Seq[Shift] correctly" in {
    val json =
      """
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
    json should include("Afternoon")
    json should include("Morning")
    json should include("Late")
    json should include("\"endDate\":null")
    json should include("\"endDate\":{\"year\":2025,\"month\":12,\"day\":1}")
  }

}
