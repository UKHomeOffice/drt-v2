package drt.client.services

import drt.client.services.JSDateConversions.SDate
import drt.shared._
import uk.gov.homeoffice.drt.ports.Terminals.T1
import utest._

import scala.scalajs.js.Date
import scala.util.{Failure, Success, Try}

object ShiftsServiceTests extends TestSuite {

  def tests: Tests = Tests {
    test("StaffShifts") - {
      test("As an HO, either in planning or at start of shift, " +
        "I want to be able tell DRT about staff available by shift for a given period" +
        "So that I can easily get an initial state for the system") - {

        test("Given a shift of 10 people, if we ask how many staff are available") - {
          val shifts = StaffAssignment("alpha", T1, SDate(2016, 12, 10, 10).millisSinceEpoch, SDate(2016, 12, 10, 19).millisSinceEpoch, 10, None)
          val shiftService = ShiftAssignments(shifts :: Nil)

          test("at its first bound, then we get 10") - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 10), JSDateConversions.longToSDateLocal)
            assert(result == 10)
          }
          test("at its upper bound, then we get 10") - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 19), JSDateConversions.longToSDateLocal)
            assert(result == 10)
          }
          test("can compare dates") - {
            assert(SDate(2015, 10, 10, 10, 10) < SDate(2016, 12, 12, 12, 12))
            assert(SDate(2015, 10, 10, 10, 10) <= SDate(2016, 12, 12, 12, 12))
          }
        }

        test("some implicits make things nicer whether we're server side or client side ") - {
          val startDt = SDate(2016, 12, 10, 10)
          val endDate = SDate(2016, 12, 19, 12)
          val shifts = StaffAssignment("alpha", T1, startDt.millisSinceEpoch, endDate.millisSinceEpoch, 10, None)

          val result = StaffAssignment("alpha", T1, 1481364000000L, 1482148800000L, 10, createdBy = None)
          assert(shifts == result)
        }

        test("Given two overlapping assignments") - {
          val shifts = StaffAssignment("alpha", T1, SDate(2016, 12, 10, 10).millisSinceEpoch, SDate(2016, 12, 10, 19).millisSinceEpoch, 10, None) ::
            StaffAssignment("beta", T1, SDate(2016, 12, 10, 18).millisSinceEpoch, SDate(2016, 12, 10, 23).millisSinceEpoch, 5, None) :: Nil
          val shiftService = ShiftAssignments(shifts)
          test("on the overlap the staff is the sum of both") - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 18, 30), JSDateConversions.longToSDateLocal)
            assert(result == 15)
          }
          test("on the lower bound of the second shift the staff is the sum of both (15)") - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 18), JSDateConversions.longToSDateLocal)
            assert(result == 15)
          }
          test("on the upper bound of the second shift the staff the number of the second shift (5)") - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 23), JSDateConversions.longToSDateLocal)
            assert(result == 5)
          }
          test("after the upper bound of the second shift the staff is 0") - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 23, 1), JSDateConversions.longToSDateLocal)
            assert(result == 0)
          }
          test("before the lower bound of the first shift the staff is 0") - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 9, 59), JSDateConversions.longToSDateLocal)
            assert(result == 0)
          }
        }

        test("StaffAssignment parsing ") - {
          test("Parse a single shift line") - {
            val shiftsRawCsv =
              """
                |StaffAssignment 1,T1,01/12/16,06:30,15:18,2
                  """.stripMargin

            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            assert(shifts == Success(StaffAssignment("StaffAssignment 1", T1, SDate(2016, 12, 1, 6, 30).millisSinceEpoch, SDate(2016, 12, 1, 15, 18).millisSinceEpoch, 2, None)) :: Nil)
          }

          test("Parse a couple of shift lines") - {
            val shiftsRawCsv =
              """
                |StaffAssignment 1,T1,01/12/16,06:30,15:18,2
                |StaffAssignment 2,T1,01/12/16,19:00,22:24,4
                  """.stripMargin

            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            val expectedShifts = Success(StaffAssignment("StaffAssignment 1", T1, SDate(2016, 12, 1, 6, 30).millisSinceEpoch, SDate(2016, 12, 1, 15, 18).millisSinceEpoch, 2, None)) ::
              Success(StaffAssignment("StaffAssignment 2", T1, SDate(2016, 12, 1, 19).millisSinceEpoch, SDate(2016, 12, 1, 22, 24).millisSinceEpoch, 4, None)) ::
              Nil
            assert(shifts == expectedShifts
            )
          }

          test("-ve numbers are fine in movements") - {
            val shiftsRawCsv =
              """
                |StaffAssignment 1,T1,01/12/16,06:30,15:18,-2
                  """.stripMargin

            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            val expectedShifts = Success(StaffAssignment("StaffAssignment 1", T1, SDate(2016, 12, 1, 6, 30).millisSinceEpoch, SDate(2016, 12, 1, 15, 18).millisSinceEpoch, -2, None)) :: Nil
            assert(shifts == expectedShifts)
          }

          test("We can make comments by not using commas") - {
            val shiftsRawCsv =
              """
                |# here be a comment - not because of the hash but because no commas
                |StaffAssignment 1,T1,01/12/16,06:30,15:18,-2
                  """.stripMargin

            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            val expectedShifts = Success(StaffAssignment("StaffAssignment 1", T1, SDate(2016, 12, 1, 6, 30).millisSinceEpoch, SDate(2016, 12, 1, 15, 18).millisSinceEpoch, -2, None)) :: Nil
            assert(shifts == expectedShifts)
          }

          test("empty lines are ignored") - {
            val shiftsRawCsv =
              """
                |
                |StaffAssignment 1,T1,01/12/16,06:30,15:18,-2
                  """.stripMargin

            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            val expectedShifts = Success(StaffAssignment("StaffAssignment 1", T1, SDate(2016, 12, 1, 6, 30).millisSinceEpoch, SDate(2016, 12, 1, 15, 18).millisSinceEpoch, -2, None)) :: Nil
            assert(shifts == expectedShifts)
          }

          test("failure to read a date will return an error for that line") - {
            val shiftsRawCsv =
              """
                |Bad line,T1,01/1b/16,06:30,15:18,-2
                  """.stripMargin

            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            shifts match {
              case Failure(_) :: Nil => assert(true)
              case other =>
                println(s"Should have failed that bad line $other")
                assert(false)
            }
          }
        }

        test("StaffAssignment to csv string representation") - {
          test("Given a shift when I ask for a csv string then I should get a string with the fields separated by commas") - {
            val shiftTry: Try[StaffAssignment] = StaffAssignmentHelper.tryStaffAssignment("My shift", T1.toString, "01/01/17", "08:00", "11:59", "2", None)
            val shift = shiftTry.get

            val csvString = StaffAssignmentHelper.toCsv(shift)

            val expected = "My shift,T1,01/01/17,08:00,11:59,2"

            assert(csvString == expected)
          }
        }

        test("Given all the assignments") - {
          test("Staff movements") - {
            test("escaped commas are allowed in shift name") - {
              val shiftsRawCsv =
                """
                  |Alpha\, 1 ODM,T1,01/12/16,06:30,15:18,1
                    """.stripMargin
              val parsedShift: Try[StaffAssignment] = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.head

              parsedShift match {
                case Success(StaffAssignment(name, _, _, _, _, _)) => assert(name == "Alpha\\, 1 ODM")
                case Failure(_) => assert(1 == 2)
              }
            }
          }

          test("Staff for a terminal should") - {
            import StaffMovements._
            val shiftsRaw =
              """
                |Alpha,T1,10/12/16,08:00,16:00,10
                  """.stripMargin

            val shiftService = ShiftAssignments(StaffAssignmentParser(shiftsRaw).parsedAssignments.collect { case Success(sa) => sa })

            test("Contain staff for a terminal shift") - {
              val sDate = SDate(2016, 12, 10, 10)
              val result = terminalStaffAt(shiftService)(Nil)(T1, sDate, JSDateConversions.longToSDateLocal)

              assert(result == 10)
            }

            test("Fixed Points should only be included for the time specified") - {
              val sDate = SDate(2016, 12, 10, 15)
              val result = terminalStaffAt(shiftService)(Nil)(T1, sDate, JSDateConversions.longToSDateLocal)

              assert(result == 10)
            }
          }
        }
      }
    }
  }

  def parseRawTsv(shiftsRawTsv: String): Array[Try[StaffAssignment]] = {
    val lines = shiftsRawTsv.split("\n")
    val parsedShifts = lines.map(l => l.split("\t"))
      .filter(_.length == 5)
      .map(pl => StaffAssignmentHelper.tryStaffAssignment(pl(0), pl(1), pl(2), pl(3), pl(4), "1", None))
    parsedShifts
  }
}
