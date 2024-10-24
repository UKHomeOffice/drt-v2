package uk.gov.homeoffice.drt.service.staffing

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.homeoffice.drt.db.tables.PortTerminalShiftConfig
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

class PortTerminalShiftConfigTest extends AnyFlatSpec with Matchers {

  "uPickle" should "correctly serialize PortTerminalShiftConfig from object to JSON" in {
    val expectedJson =
    """{"port":"LHR","terminal":"T1","shiftName":"Shift Name","startAt":"1633027200000","periodInMinutes":120,"endAt":1633030800000,"frequency":"Daily","actualStaff":10,"minimumRosteredStaff":8,"updatedAt":"1633030800000","email":"example@example.com"}""".stripMargin

    val portTerminalShiftConfig = PortTerminalShiftConfig(
      port = PortCode("LHR"),
      terminal = Terminal("T1"),
      shiftName = "Shift Name",
      startAt = 1633027200000L,
      periodInMinutes = 120,
      endAt = Some(1633030800000L),
      frequency = Some("Daily"),
      actualStaff = Some(10),
      minimumRosteredStaff = Some(8),
      updatedAt = 1633030800000L,
      email = "example@example.com"
    )

    val portTerminalShiftJson = PortTerminalShiftConfigJsonSerializer.writeToJson(portTerminalShiftConfig)

    portTerminalShiftJson shouldEqual expectedJson
  }
}
