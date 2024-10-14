package uk.gov.homeoffice.drt.service.staffing
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

class PortTerminalShiftTest extends AnyFlatSpec with Matchers {

  "uPickle" should "correctly deserialize PortTerminalShift from JSON" in {
    val json = """{
      "port": "PortCode",
      "terminal": "Terminal",
      "shiftName": "Shift Name",
      "startAt": 1633027200000,
      "periodInMinutes": 120,
      "endAt": 1633030800000,
      "frequency": "Daily",
      "actualStaff": 10,
      "minimumRosteredStaff": 8,
      "email": "example@example.com"
    }"""

    val expectedPortTerminalShift = PortTerminalShift(
      port = "PortCode",
      terminal = "Terminal",
      shiftName = "Shift Name",
      startAt = 1633027200000L,
      periodInMinutes = 120,
      endAt = Some(1633030800000L),
      frequency = Some("Daily"),
      actualStaff = Some(10),
      minimumRosteredStaff = Some(8),
      email = "example@example.com"
    )

    import PortTerminalShiftJsonSerializer.portTerminalShiftRW
    val actualPortTerminalShift = read[PortTerminalShift](json,true)

    actualPortTerminalShift shouldEqual expectedPortTerminalShift
  }
}
