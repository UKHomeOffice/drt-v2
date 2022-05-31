package services.`export`

import java.util.UUID

import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared.{MilliDate, StaffMovement}
import org.specs2.mutable.Specification
import services.SDate
import services.exports.StaffMovementsExport

class StaffMovementsExportSpec extends Specification {

  "Given a list of staff movements with 1 pair of movements " +
    "Then we should see that represented by 2 CSV rows" >> {
    val uuid = newUuidString
    val movements = List(
      StaffMovement(Terminal("T1"), "test", MilliDate(SDate("2020-07-07T12:00:00Z").millisSinceEpoch), 2, uuid, None, Option("test@test.com")),
      StaffMovement(Terminal("T1"), "test", MilliDate(SDate("2020-07-07T13:00:00Z").millisSinceEpoch), -2, uuid, None, Option("test@test.com"))
    )

    val expected =
      """|T1,test,2020-07-07 13:00,2,test@test.com
         |T1,test,2020-07-07 14:00,-2,test@test.com""".stripMargin

    val result = StaffMovementsExport.toCSV(movements, Terminal("T1"))

    result === expected
  }

  private def newUuidString = UUID.randomUUID().toString

  "Given a list of staff movements with 2 pairs of movements " +
    "Then we should see that represented by 4 CSV rows" >> {
    val uuid = newUuidString
    val uuid2 = newUuidString
    val movements = List(
      StaffMovement(Terminal("T1"), "test", MilliDate(SDate("2020-07-07T12:00:00Z").millisSinceEpoch), 2, uuid, None, Option("test@test.com")),
      StaffMovement(Terminal("T1"), "test2", MilliDate(SDate("2020-07-07T12:15:00Z").millisSinceEpoch), 4, uuid2, None, Option("test@test.com")),
      StaffMovement(Terminal("T1"), "test", MilliDate(SDate("2020-07-07T13:00:00Z").millisSinceEpoch), -2, uuid, None, Option("test@test.com")),
      StaffMovement(Terminal("T1"), "test2", MilliDate(SDate("2020-07-07T16:00:00Z").millisSinceEpoch), -4, uuid2, None, Option("test@test.com")),
    )

    val expected =
      """|T1,test,2020-07-07 13:00,2,test@test.com
         |T1,test2,2020-07-07 13:15,4,test@test.com
         |T1,test,2020-07-07 14:00,-2,test@test.com
         |T1,test2,2020-07-07 17:00,-4,test@test.com""".stripMargin

    val result = StaffMovementsExport.toCSV(movements, Terminal("T1"))

    result === expected
  }

  "Given a list of staff movements with 2 pairs of movements for two terminals " +
    "When we request the staff movements for T1"+
    "Then we should only see the T1 movements" >> {
    val uuid = newUuidString
    val uuid2 = newUuidString
    val movements = List(
      StaffMovement(Terminal("T1"), "test", MilliDate(SDate("2020-07-07T12:00:00Z").millisSinceEpoch), 2, uuid, None, Option("test@test.com")),
      StaffMovement(Terminal("T2"), "test2", MilliDate(SDate("2020-07-07T12:15:00Z").millisSinceEpoch), 4, uuid2, None, Option("test@test.com")),
      StaffMovement(Terminal("T1"), "test", MilliDate(SDate("2020-07-07T13:00:00Z").millisSinceEpoch), -2, uuid, None, Option("test@test.com")),
      StaffMovement(Terminal("T2"), "test2", MilliDate(SDate("2020-07-07T16:00:00Z").millisSinceEpoch), -4, uuid2, None, Option("test@test.com")),
    )

    val expected =
      """|T1,test,2020-07-07 13:00,2,test@test.com
         |T1,test,2020-07-07 14:00,-2,test@test.com""".stripMargin

    val result = StaffMovementsExport.toCSV(movements, Terminal("T1"))

    result === expected
  }

  "Given a list of staff movements with 2 pairs of movements for two terminals " +
    "When we request the staff movements with headers for T1 "+
    "Then we should only see the T1 movements with a header row" >> {
    val uuid = newUuidString
    val uuid2 = newUuidString
    val movements = List(
      StaffMovement(Terminal("T1"), "test", MilliDate(SDate("2020-07-07T12:00:00Z").millisSinceEpoch), 2, uuid, None, Option("test@test.com")),
      StaffMovement(Terminal("T2"), "test2", MilliDate(SDate("2020-07-07T12:15:00Z").millisSinceEpoch), 4, uuid2, None, Option("test@test.com")),
      StaffMovement(Terminal("T1"), "test", MilliDate(SDate("2020-07-07T13:00:00Z").millisSinceEpoch), -2, uuid, None, Option("test@test.com")),
      StaffMovement(Terminal("T2"), "test2", MilliDate(SDate("2020-07-07T16:00:00Z").millisSinceEpoch), -4, uuid2, None, Option("test@test.com")),
    )

    val expected =
      """|Terminal,Reason,Time,Staff Change,Made by
         |T1,test,2020-07-07 13:00,2,test@test.com
         |T1,test,2020-07-07 14:00,-2,test@test.com""".stripMargin

    val result = StaffMovementsExport.toCSVWithHeader(movements, Terminal("T1"))

    result === expected
  }

}
