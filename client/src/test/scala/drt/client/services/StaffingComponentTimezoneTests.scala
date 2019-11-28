package drt.client.services

import drt.client.services.JSDateConversions.SDate
import drt.shared.Terminals.T1
import drt.shared._
import utest._

object StaffingComponentTimezoneTests extends TestSuite {

  import drt.client.components.MonthlyStaffing._


  def tests = Tests {
    val utcToBSTChangeDate = SDate("2019-03-31")

    "When inputing data to the monthly staffing table and the timezone changes from UTC to BST" - {
      "Given 2019-03-31 (timezone change day) I should get back a list of 23 hours of time slots not the usual 24" - {
        val result = slotsInDay(utcToBSTChangeDate, 60).size

        val expected = 23

        assert(result == expected)
      }
      "Given 2019-03-30  (regular UTC day) I should get back a list of 24 hours of time slots" - {
        val date = SDate("2019-03-30")
        val result = slotsInDay(date, 60).size

        val expected = 24

        assert(result == expected)
      }
      "Given I pass timezone change day time slots into the time zone day slots method then I should get back " +
        "None for the slots that don't exist" - {

        val changeDayTimeslots: Seq[SDateLike] = slotsInDay(utcToBSTChangeDate, 60)

        val result: List[Option[SDateLike]] = timeZoneSafeTimeSlots(changeDayTimeslots, 60).take(3).toList

        val expected = Seq(
          Some(SDate("2019-03-31T00:00:00Z")),
          None,
          Some(SDate("2019-03-31T01:00:00Z"))
        )

        assert(result == expected)
      }

      "Given I pass timezone change day time slots into the time zone day slots method then I should get back " +
        "None for the slots that don't exist when using 15 minute time slots" - {

        val changeDayTimeslots: Seq[SDateLike] = slotsInDay(utcToBSTChangeDate, 15)

        val result: List[Option[SDateLike]] = timeZoneSafeTimeSlots(changeDayTimeslots, 15).take(12).toList

        val expected = Seq(
          Some(SDate("2019-03-31T00:00:00Z")),
          Some(SDate("2019-03-31T00:15:00Z")),
          Some(SDate("2019-03-31T00:30:00Z")),
          Some(SDate("2019-03-31T00:45:00Z")),
          None,
          None,
          None,
          None,
          Some(SDate("2019-03-31T01:00:00Z")),
          Some(SDate("2019-03-31T01:15:00Z")),
          Some(SDate("2019-03-31T01:30:00Z")),
          Some(SDate("2019-03-31T01:45:00Z"))
        )

        assert(result == expected)
      }

      "Given I pass 2019-03-31 change day time slots into the time zone day slots method then the last time slot" +
        " should be 2019-03-31T23:00:00Z when using 1 hour time slots" - {

        val changeDayTimeslots: Seq[SDateLike] = slotsInDay(utcToBSTChangeDate, 60)

        val result: Option[String] = timeZoneSafeTimeSlots(changeDayTimeslots, 60).toList.last.map(_.toISOString())
        val expected = Some(SDate("2019-03-31T23:00:00").toISOString())

        assert(result == expected)
      }

      "Given I pass 2019-03-31 change day time slots into the time zone day slots method then the last time slot" +
        " should be 2019-03-31T23:45:00Z when using 15 minute time slots" - {

        val changeDayTimeslots: Seq[SDateLike] = slotsInDay(utcToBSTChangeDate, 15)

        val result: Option[String] = timeZoneSafeTimeSlots(changeDayTimeslots, 15).toList.last.map(_.toISOString())
        val expected = Option(SDate("2019-03-31T23:45:00").toISOString())

        assert(result == expected)
      }

      "Given 2019-01-01 when retrieving days in month by time slot with 1 hour slots I should get back a 24x31 matrix" - {
        val startDate = SDate("2019-01-01")
        val result: Seq[Seq[Option[SDateLike]]] = daysInMonthByTimeSlot((startDate, 60))
        val expectedHeight = 24

        assert(result.size == expectedHeight)
        result.foreach(row => assert(row.size == 31))
      }

      "Given 2019-01-01 when retrieving days in month by time slot with 1 hour slots row 1 should be midnight for " +
        "every day of the month" - {
        val startDate = SDate("2019-01-01T00:00:00Z")
        val result: Seq[Option[SDateLike]] = daysInMonthByTimeSlot((startDate, 60)).head
        val expected = List.tabulate(31)(d => Option(startDate.addDays(d)))

        assert(result == expected)
      }

      "Given 2019-03-01 the final day in row 2 should be None (handling switch to BST)" - {
        val startDate = SDate("2019-03-01T00:00:00Z")
        val oneAmTimeslot = daysInMonthByTimeSlot((startDate, 60))(1)

        val result: Option[SDateLike] = oneAmTimeslot(30)
        val expected = None

        assert(result == expected)
      }
      "When handling user changes to the monthly staffing table and the timezone changes from UTC to BST" - {

        "Given 1 staff member is entered for 2019-03-31T00:00:00Z I should get an updated StaffAssignment for that" +
          " 15 minute slot" - {
          val change: Map[(Int, Int), Int] = Map((0, 30) -> 1)
          val startOfMonth = SDate("2019-03-01")
          val result = updatedShiftAssignments(change, startOfMonth, T1, 15).head

          val shiftStart = SDate("2019-03-31T00:00:00Z")
          val expected = StaffAssignment(
            shiftStart.toISOString(),
            T1,
            MilliDate(shiftStart.millisSinceEpoch),
            MilliDate(shiftStart.addMinutes(14).millisSinceEpoch),
            1,
            None
          )

          assert(result == expected)
        }

        "Given 1 staff member is entered for 2019-03-31T00:00:00Z I should get an updated StaffAssignment for that" +
          " 1 hour slot" - {
          val change: Map[(Int, Int), Int] = Map((0, 30) -> 1)
          val startOfMonth = SDate("2019-03-01")
          val result = updatedShiftAssignments(change, startOfMonth, T1, 60).head

          val shiftStart = SDate("2019-03-31T00:00:00Z")
          val expected = StaffAssignment(
            shiftStart.toISOString(),
            T1,
            MilliDate(shiftStart.millisSinceEpoch),
            MilliDate(shiftStart.addMinutes(59).millisSinceEpoch),
            1,
            None
          )

          assert(result == expected)
        }

        "Given 1 staff member is entered for 2019-03-30T01:00:00Z I should get an updated StaffAssignment for that" +
          " 1 hour slot" - {
          val change: Map[(Int, Int), Int] = Map((1, 29) -> 1)
          val startOfMonth = SDate("2019-03-01")
          val result = updatedShiftAssignments(change, startOfMonth, T1, 60).head

          val shiftStart = SDate("2019-03-30T01:00:00Z")
          val expected = StaffAssignment(
            shiftStart.toISOString(),
            T1,
            MilliDate(shiftStart.millisSinceEpoch),
            MilliDate(shiftStart.addMinutes(59).millisSinceEpoch),
            1,
            None
          )

          assert(result == expected)
        }

        "Given 1 staff member is entered for 2019-03-31T01:00:00Z (timezone swtich time) I should get an updated " +
          "StaffAssignment for that 1 hour slot - which is the 3rd slot for the day" - {
          val change: Map[(Int, Int), Int] = Map((2, 30) -> 1)
          val startOfMonth = SDate("2019-03-01")
          val result = updatedShiftAssignments(change, startOfMonth, T1, 60).head

          val shiftStart = SDate("2019-03-31T01:00:00Z")
          val expected = StaffAssignment(
            shiftStart.toISOString(),
            T1,
            MilliDate(shiftStart.millisSinceEpoch),
            MilliDate(shiftStart.addMinutes(59).millisSinceEpoch),
            1,
            None
          )

          assert(result == expected)
        }
      }
    }

    "When inputing data to the monthly staffing table and the timezone changes from BST to UTC" - {
      val bstToUtcChangeDate = SDate("2019-10-27")
      "Given 2019-10-27 (timezone change day) I should get back a list of 25 hours of time slots not the usual 24" - {
        val result = slotsInDay(bstToUtcChangeDate, 60).size

        val expected = 25

        assert(result == expected)
      }

      "Given I pass timezone change day time slots into the time zone day slots method then I should get back no empty slots" - {

        val changeDayTimeslots: Seq[SDateLike] = slotsInDay(bstToUtcChangeDate, 15)

        val result: List[Option[SDateLike]] = timeZoneSafeTimeSlots(changeDayTimeslots, 15).take(16).toList

        val expected = Seq(
          Some(SDate("2019-10-26T23:00:00Z")),
          Some(SDate("2019-10-26T23:15:00Z")),
          Some(SDate("2019-10-26T23:30:00Z")),
          Some(SDate("2019-10-26T23:45:00Z")),
          Some(SDate("2019-10-27T00:00:00Z")),
          Some(SDate("2019-10-27T00:15:00Z")),
          Some(SDate("2019-10-27T00:30:00Z")),
          Some(SDate("2019-10-27T00:45:00Z")),
          Some(SDate("2019-10-27T01:00:00Z")),
          Some(SDate("2019-10-27T01:15:00Z")),
          Some(SDate("2019-10-27T01:30:00Z")),
          Some(SDate("2019-10-27T01:45:00Z")),
          Some(SDate("2019-10-27T02:00:00Z")),
          Some(SDate("2019-10-27T02:15:00Z")),
          Some(SDate("2019-10-27T02:30:00Z")),
          Some(SDate("2019-10-27T02:45:00Z"))
        )

        assert(result == expected)
      }
      "Given I pass timezone another October day time slots into the time zone day slots method then I should " +
        "get back empty slots for the row where the timezone change day has an extra 2am with 60 minute slots" - {

        val changeDayTimeslots: Seq[SDateLike] = slotsInDay(SDate("2019-10-26"), 60)

        val result: List[Option[SDateLike]] = timeZoneSafeTimeSlots(changeDayTimeslots, 60).take(5).toList

        val expected = Seq(
          Some(SDate("2019-10-25T23:00:00Z")),
          Some(SDate("2019-10-26T00:00:00Z")),
          None,
          Some(SDate("2019-10-26T01:00:00Z")),
          Some(SDate("2019-10-26T02:00:00Z"))
        )

        assert(result == expected)
      }
      "Given I pass timezone another October day time slots into the time zone day slots method then I should " +
        "get back empty slots for the row where the timezone change day has an extra 2am with 15 minute slots" - {

        val changeDayTimeslots: Seq[SDateLike] = slotsInDay(SDate("2019-10-26"), 15)

        val result: List[Option[SDateLike]] = timeZoneSafeTimeSlots(changeDayTimeslots, 15).take(20).toList

        val expected = Seq(
          Some(SDate("2019-10-25T23:00:00Z")),
          Some(SDate("2019-10-25T23:15:00Z")),
          Some(SDate("2019-10-25T23:30:00Z")),
          Some(SDate("2019-10-25T23:45:00Z")),
          Some(SDate("2019-10-26T00:00:00Z")),
          Some(SDate("2019-10-26T00:15:00Z")),
          Some(SDate("2019-10-26T00:30:00Z")),
          Some(SDate("2019-10-26T00:45:00Z")),
          None,
          None,
          None,
          None,
          Some(SDate("2019-10-26T01:00:00Z")),
          Some(SDate("2019-10-26T01:15:00Z")),
          Some(SDate("2019-10-26T01:30:00Z")),
          Some(SDate("2019-10-26T01:45:00Z")),
          Some(SDate("2019-10-26T02:00:00Z")),
          Some(SDate("2019-10-26T02:15:00Z")),
          Some(SDate("2019-10-26T02:30:00Z")),
          Some(SDate("2019-10-26T02:45:00Z"))
        )

        assert(result == expected)
      }
    }
  }

}
