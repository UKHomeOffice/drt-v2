package drt.client.services

import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
import utest._

import scala.language.implicitConversions
import scala.scalajs.js.Date
import scala.util.{Failure, Success, Try}

object ShiftsServiceTests extends TestSuite {

  import drt.client.services.JSDateConversions._

  def tests = Tests {
    'StaffShifts - {
      "As an HO, either in planning or at start of shift, " +
        "I want to be able tell DRT about staff available by shift for a given period" +
        "So that I can easily get an initial state for the system" - {

        "Given a shift of 10 people, if we ask how many staff are available" - {
          val shifts = StaffAssignment("alpha", T1, SDate(2016, 12, 10, 10, 0), SDate(2016, 12, 10, 19, 0), 10, None)
          val shiftService = ShiftAssignments(shifts :: Nil)

          "at its first bound, then we get 10" - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 10, 0))
            assert(result == 10)
          }
          "at its upper bound, then we get 10" - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 19, 0))
            assert(result == 10)
          }
          "can compare dates" - {
            assert(SDate(2015, 10, 10, 10, 10) < SDate(2016, 12, 12, 12, 12))
            assert(SDate(2015, 10, 10, 10, 10) <= SDate(2016, 12, 12, 12, 12))
          }
        }

        "some implicits make things nicer whether we're server side or client side " - {
          val startDt = SDate(2016, 12, 10, 10, 0)
          val endDate = SDate(2016, 12, 19, 12, 0)
          val shifts = StaffAssignment("alpha", T1, startDt, endDate, 10, None)

          val result = StaffAssignment("alpha", T1, 1481364000000L, 1482148800000L, 10, createdBy = None)
          assert(shifts == result)
        }

        "Given two overlapping assignments" - {
          val shifts = StaffAssignment("alpha", T1, SDate(2016, 12, 10, 10, 0), SDate(2016, 12, 10, 19, 0), 10, None) ::
            StaffAssignment("beta", T1, SDate(2016, 12, 10, 18, 0), SDate(2016, 12, 10, 23, 0), 5, None) :: Nil
          val shiftService = ShiftAssignments(shifts)
          "on the overlap the staff is the sum of both" - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 18, 30))
            assert(result == 15)
          }
          "on the lower bound of the second shift the staff is the sum of both (15)" - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 18, 0))
            assert(result == 15)
          }
          "on the upper bound of the second shift the staff the number of the second shift (5)" - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 23, 0))
            assert(result == 5)
          }
          "after the upper bound of the second shift the staff is 0" - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 23, 1))
            assert(result == 0)
          }
          "before the lower bound of the first shift the staff is 0" - {
            val result = shiftService.terminalStaffAt(T1, SDate(2016, 12, 10, 9, 59))
            assert(result == 0)
          }
        }

        "StaffAssignment parsing " - {
          "Parse a single shift line" - {
            val shiftsRawCsv =
              """
                |StaffAssignment 1,T1,01/12/16,06:30,15:18,2
              """.stripMargin

            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            assert(shifts == Success(StaffAssignment("StaffAssignment 1", T1, SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), 2, None)) :: Nil)
          }

          " Parse a couple of shift lines" - {
            val shiftsRawCsv =
              """
                |StaffAssignment 1,T1,01/12/16,06:30,15:18,2
                |StaffAssignment 2,T1,01/12/16,19:00,22:24,4
              """.stripMargin

            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            val expectedShifts = Success(StaffAssignment("StaffAssignment 1", T1, SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), 2, None)) ::
              Success(StaffAssignment("StaffAssignment 2", T1, SDate(2016, 12, 1, 19, 0), SDate(2016, 12, 1, 22, 24), 4, None)) ::
              Nil
            assert(shifts == expectedShifts
            )
          }

          "-ve numbers are fine in movements" - {
            val shiftsRawCsv =
              """
                |StaffAssignment 1,T1,01/12/16,06:30,15:18,-2
              """.stripMargin

            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            val expectedShifts = Success(StaffAssignment("StaffAssignment 1", T1, SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), -2, None)) :: Nil
            assert(shifts == expectedShifts)
          }

          "We can make comments by not using commas" - {
            val shiftsRawCsv =
              """
                |# here be a comment - not because of the hash but because no commas
                |StaffAssignment 1,T1,01/12/16,06:30,15:18,-2
              """.stripMargin

            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            val expectedShifts = Success(StaffAssignment("StaffAssignment 1", T1, SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), -2, None)) :: Nil
            assert(shifts == expectedShifts)
          }

          "empty lines are ignored" - {
            val shiftsRawCsv =
              """
                |
                |StaffAssignment 1,T1,01/12/16,06:30,15:18,-2
              """.stripMargin

            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            val expectedShifts = Success(StaffAssignment("StaffAssignment 1", T1, SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), -2, None)) :: Nil
            assert(shifts == expectedShifts)
          }

          "failure to read a date will return an error for that line" - {
            val shiftsRawCsv =
              """
                |Bad line,T1,01/1b/16,06:30,15:18,-2
              """.stripMargin

            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            shifts match {
              case Failure(t) :: Nil => assert(true)
              case other =>
                println(s"Should have failed that bad line $other")
                assert(false)
            }
          }
        }

        "StaffAssignment to csv string representation" - {
          "Given a shift when I ask for a csv string then I should get a string with the fields separated by commas" - {
            val shiftTry: Try[StaffAssignment] = StaffAssignmentHelper.tryStaffAssignment("My shift", T1.toString, "01/01/17", "08:00", "11:59", "2")
            val shift = shiftTry.get

            val csvString = StaffAssignmentHelper.toCsv(shift)

            val expected = "My shift,T1,01/01/17,08:00,11:59,2"

            assert(csvString == expected)
          }
        }

        "Given all the assignments" - {
          "Staff movements" - {
            "escaped commas are allowed in shift name" - {
              val shiftsRawCsv =
                """
                  |Alpha\, 1 ODM,T1,01/12/16,06:30,15:18
                """.stripMargin
              val parsedShift: Try[StaffAssignment] = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.head

              parsedShift match {
                case Success(StaffAssignment(name, _, _, _, _, _)) => assert(name == "Alpha\\, 1 ODM")
                case Failure(_) => assert(1 == 2)
              }
            }
          }

          "Staff for a terminal should" - {
            import StaffMovements._
            val shiftsRaw =
              """
                |Alpha,T1,10/12/16,08:00,16:00,10
              """.stripMargin

            val shiftService = ShiftAssignments(StaffAssignmentParser(shiftsRaw).parsedAssignments.collect { case Success(sa) => sa })

            "Contain staff for a terminal shift" - {
              val sDate = SDate(2016, 12, 10, 10, 0)
              val result = terminalStaffAt(shiftService)(Nil)(T1, sDate)

              assert(result == 10)
            }

            "Fixed Points should only be included for the time specified" - {
              val sDate = SDate(2016, 12, 10, 15, 0)
              val result = terminalStaffAt(shiftService)(Nil)(T1, sDate)

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
      .map(pl => StaffAssignmentHelper.tryStaffAssignment(pl(0), pl(1), pl(2), pl(3), pl(4)))
    parsedShifts
  }
}

object TestTimer {
  def timeIt(name: String)(times: Int)(f: => Unit): Unit = {
    val start = new Date()
    println(s"$name: Starting timer at $start")
    (1 to times).foreach(n => {
      println(n)
      f
    })
    val end = new Date()
    println(s"$name Trial done at $end")
    val timeTaken = end.getTime() - start.getTime()
    println(s"$name Time taken in $times runs ${timeTaken}ms, ${timeTaken.toDouble / times} per run")
  }
}
