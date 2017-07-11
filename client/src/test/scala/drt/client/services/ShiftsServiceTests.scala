package drt.client.services

import java.util.UUID

import diode.data.Ready
import drt.shared.StaffMovement
import utest._

import scala.scalajs.js.Date
import scala.util.{Failure, Success, Try}

object ShiftsServiceTests extends TestSuite {

  import drt.client.services.JSDateConversions._

  def tests = TestSuite {
    'StaffShifts - {
      "As an HO, either in planning or at start of shift, " +
        "I want to be able tell DRT about staff available by shift for a given period" +
        "So that I can easily get an initial state for the system" - {


        "Given a shift of 10 people, if we ask how many staff are available" - {
          val shifts = StaffAssignment("alpha", "any", SDate(2016, 12, 10, 10, 0), SDate(2016, 12, 10, 19, 0), 10)
          val shiftService = StaffAssignmentServiceWithoutDates(shifts :: Nil)
          "at its first bound, then we get 10" - {
            assert(shiftService.staffAt(SDate(2016, 12, 10, 10, 0)) == 10)
          }
          "at its upper bound, then we get 10" - {
            assert(shiftService.staffAt(SDate(2016, 12, 10, 19, 0)) == 10)
          }
          "can compare dates" - {
            assert(SDate(2015, 10, 10, 10, 10) < SDate(2016, 12, 12, 12, 12))
            assert(SDate(2015, 10, 10, 10, 10) <= SDate(2016, 12, 12, 12, 12))
          }
        }

        "some implicits make things nicer whether we're server side or client side " - {
          val startDt = SDate(2016, 12, 10, 10, 0)
          val endDate = SDate(2016, 12, 19, 12, 0)
          val shifts = StaffAssignment("alpha", "any", startDt, endDate, 10)

          assert(shifts == StaffAssignment("alpha", "any", 1481364000000L, 1482148800000L, 10))
        }

        "Given two overlapping assignments" - {
          val shifts = StaffAssignment("alpha", "any", SDate(2016, 12, 10, 10, 0), SDate(2016, 12, 10, 19, 0), 10) ::
            StaffAssignment("beta", "any", SDate(2016, 12, 10, 18, 0), SDate(2016, 12, 10, 23, 0), 5) :: Nil
          val shiftService = StaffAssignmentServiceWithoutDates(shifts)
          "on the overlap the staff is the sum of both" - {
            assert(shiftService.staffAt(SDate(2016, 12, 10, 18, 30)) == 15)
          }
          "on the lower bound of the second shift the staff is the sum of both (15)" - {
            assert(shiftService.staffAt(SDate(2016, 12, 10, 18, 0)) == 15)
          }
          "on the upper bound of the second shift the staff the number of the second shift (5)" - {
            assert(shiftService.staffAt(SDate(2016, 12, 10, 23, 0)) == 5)
          }
          "after the upper bound of the second shift the staff is 0" - {
            assert(shiftService.staffAt(SDate(2016, 12, 10, 23, 1)) == 0)
          }
          "before the lower bound of the first shift the staff is 0" - {
            assert(shiftService.staffAt(SDate(2016, 12, 10, 9, 59)) == 0)
          }
        }

        "StaffAssignment parsing " - {
          "Parse a single shift line" - {
            val shiftsRawCsv =
              """
                |StaffAssignment 1,any,01/12/16,06:30,15:18,2
              """.stripMargin


            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            assert(shifts == Success(StaffAssignment("StaffAssignment 1", "any", SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), 2)) :: Nil)
          }

          " Parse a couple of shift lines" - {
            val shiftsRawCsv =
              """
                |StaffAssignment 1,any,01/12/16,06:30,15:18,2
                |StaffAssignment 2,any,01/12/16,19:00,22:24,4
              """.stripMargin


            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            val expectedShifts = Success(StaffAssignment("StaffAssignment 1", "any", SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), 2)) ::
              Success(StaffAssignment("StaffAssignment 2", "any", SDate(2016, 12, 1, 19, 0), SDate(2016, 12, 1, 22, 24), 4)) ::
              Nil
            assert(shifts == expectedShifts
            )
          }

          "-ve numbers are fine in movements" - {
            val shiftsRawCsv =
              """
                |StaffAssignment 1,any,01/12/16,06:30,15:18,-2
              """.stripMargin


            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            val expectedShifts = Success(StaffAssignment("StaffAssignment 1", "any", SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), -2)) :: Nil
            assert(shifts == expectedShifts)
          }

          "We can make comments by not using commas" - {
            val shiftsRawCsv =
              """
                |# here be a comment - not because of the hash but because no commas
                |StaffAssignment 1,any,01/12/16,06:30,15:18,-2
              """.stripMargin


            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            val expectedShifts = Success(StaffAssignment("StaffAssignment 1", "any", SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), -2)) :: Nil
            assert(shifts == expectedShifts)
          }

          "empty lines are ignored" - {
            val shiftsRawCsv =
              """
                |
                |StaffAssignment 1,any,01/12/16,06:30,15:18,-2
              """.stripMargin


            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            val expectedShifts = Success(StaffAssignment("StaffAssignment 1", "any", SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), -2)) :: Nil
            assert(shifts == expectedShifts)
          }

          "failure to read a date will return an error for that line" - {
            val shiftsRawCsv =
              """
                |Bad line,any,01/1b/16,06:30,15:18,-2
              """.stripMargin


            val shifts = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.toList
            shifts match {
              case Failure(t) :: Nil => assert(true)
              case other =>
                println(s"Should have failed that bad line ${other}")
                assert(false)
            }
          }
        }

        "StaffAssignment to csv string representation" - {
          "Given a shift when I ask for a csv string then I should get a string with the fields separated by commas" - {
            val shiftTry: Try[StaffAssignment] = StaffAssignment("My shift", "any", "01/01/17", "08:00", "11:59", "2")
            val shift = shiftTry.get

            val csvString = shift.toCsv

            val expected = "My shift,any,01/01/17,08:00,11:59,2"

            assert(csvString == expected)
          }
        }

        "Given all the assignments" - {
          val shiftsRawTsv =
            """
              |Alpha 1 ODM	any	01/12/16	06:30	15:18
              |Alpha 1with ODM	any	01/12/16	06:30	15:18
              |Alpha 2 /Customs from	any 0845
              |"Alpha + Immigration, Assurance"	any	01/12/16	08:00	16:48
              |"Alpha + Immigration, Assurance"	any	01/12/16	08:00	16:48
              |
              |0700-1100	any	01/12/16	07:00	11:00
              |0700-1100	any	01/12/16	07:00	11:00
              |0700-1100	any	01/12/16	07:00	11:00
              |0700-1100	any	01/12/16	07:00	11:00
              |0700-1100	any	01/12/16	07:00	11:00
              |0700-1100	any	01/12/16	07:00	11:00
              |0700-1400	any	01/12/16	07:00	14:00
              |0700-1400	any	01/12/16	07:00	14:00
              |0700-1600	any	01/12/16	07:00	16:00
              |0700-1600	any	01/12/16	07:00	16:00
              |0800-1700	any	01/12/16	08:00	17:00
              |0800-1700	any	01/12/16	08:00	17:00
              |
              |Alpha/FGY	any	01/12/16	06:00	14:48
              |Alpha/Casework	any	01/12/16	07:00	14:24
              |Alpha (D)	any	01/12/16	07:00	14:54
              |Alpha (DETECTION)	any	01/12/16	07:00	15:48
              |Alpha (D)	any	01/12/16	07:00	15:48
              |Alpha/SEA	any	01/12/16	07:00	15:48
              |Alpha	any	01/12/16	07:00	15:48
              |Alpha from 0845 with Crime Team/SB	any	01/12/16	07:00	15:48
              |Alpha	any	01/12/16	07:00	15:48
              |Alpha	any	01/12/16	07:00	15:48
              |Alpha - R	any	01/12/16	07:00	15:48
              |Alpha/OSO/SAT	any	01/12/16	07:00	15:48
              |Alpha from 0845 with Crime Team/SB	any	01/12/16	07:00	15:48
              |Alpha	any	01/12/16	07:00	15:48
              |Alpha (DETECTION)	any	01/12/16	07:00	17:00
              |
              |Training	any	01/12/16	08:00	17:24
              |Stats	any	01/12/16	09:00	16:24
              |WI	any	01/12/16	09:30	14:30
              |09:30-16:54	any	01/12/16	09:30	16:54
              |1030-1915	any	01/12/16	10:30	19:15
              |Bravo	any	01/12/16	11:00	18:24
              |Bravo	any	01/12/16	11:00	18:54
              |Bravo 1430-1700 CT training	any	01/12/16	11:00	19:48
              |Bravo - R	any	01/12/16	11:00	19:48
              |1200-2000	any	01/12/16	12:00	20:00
              |
              |Late Duty SO
              |Charlie - ODM	any	01/12/16	14:30	23:18
              |Charlie/SEA with OTO Joanne Clark	any	01/12/16	14:30	23:18
              |Charlie/SEA/SAT OTO for Alison Burbeary	any	01/12/16	14:30	23:18
              |Charlie	any	01/12/16	14:30	23:18
              |Charlie 1430-1700 CT Training (D)	any	01/12/16	14:30	23:18
              |Charlie - R	any	01/12/16	14:30	23:18
              |Charlie/SEA	any	01/12/16	14:30	23:18
              |
              |Delta/OSO (D)	any	01/12/16	16:00	00:48
              |Delta - R	any	01/12/16	16:00	00:48
              |Delta/Fgy	any	01/12/16	16:00	00:48
              |Delta/SEA with OTO M Elmhassani	any	01/12/16	16:00	00:48
              |Delta	any	01/12/16	16:00	00:48
              |Delta	any	01/12/16	16:00	00:48
              |Delta/CWK	any	01/12/16	16:00	00:48
              |Delta/SEA	any	01/12/16	16:00	00:48
              |1600-2000	any	01/12/16	16:00	20:00
              |1600-2000	any	01/12/16	16:00	20:00
              |"Delta 1 Immigration, Assurance"	any	01/12/16	16:36	01:24
              |
              |Echo/SEA OTO for K Hopkins	any	01/12/16	17:00	01:48
              |Echo	any	01/12/16	17:00	01:48
              |
              |1800-0100	any	01/12/16	18:00	01:00
              |1800-0100	any	01/12/16	18:00	01:00
              |1800-0100	any	01/12/16	18:00	01:00
              |1800-0100	any	01/12/16	18:00	01:00
              |1800-0100	any	01/12/16	18:00	01:00
              |1800-0100	any	01/12/16	18:00	01:00
              |1800-2200	any	01/12/16	18:00	22:00
              |
              |Night	any	01/12/16	22:00	08:00
              |Night - ODM	any	01/12/16	22:30	07:18
              |Night	any	01/12/16	22:30	07:18
              |Night	any	01/12/16	22:30	07:18
              |Night	any	01/12/16	22:30	07:18
              |Night	any	01/12/16	22:30	07:18
              |Night	any	01/12/16	22:30	07:18
              |Night	any	01/12/16	22:30	07:18
              |Night	any	01/12/16	22:30	07:18
              |Night	any	01/12/16	22:30	07:18
            """.stripMargin

          val parsedShifts: Array[Try[StaffAssignment]] = parseRawTsv(shiftsRawTsv)

          println(parsedShifts.mkString("\n"))

          // Commented out some performance tests we used to evaluate different approaches.

          //          "asking for a whole days shape with individual stuff" - {
          //            val assignmentService = StaffAssignmentService(parsedAssignments.toList)
          //            val startOfDay: Long = SDate(2016, 12, 1, 0, 0)
          //            val timeMinPlusOneDay: Long = startOfDay + WorkloadsHelpers.oneMinute * 60 * 36
          //            val daysWorthOf15Minutes = startOfDay until timeMinPlusOneDay by (WorkloadsHelpers.oneMinute * 15)
          //
          //            TestTimer.timeIt("individuals")(50) {
          //              val staffAtTIme = daysWorthOf15Minutes.map {
          //                time => (time) -> assignmentService.staffAt(time)
          //              }
          //            }
          //
          //          }

          //          "asking for a whole days shape with grouped staff" - {
          //            val assignmentService = StaffAssignmentService(StaffAssignmentService.groupPeopleByShiftTimes(parsedAssignments).toList)
          //            val startOfDay: Long = SDate(2016, 12, 1, 0, 0)
          //            val timeMinPlusOneDay: Long = startOfDay + WorkloadsHelpers.oneMinute * 60 * 36
          //            val daysWorthOf15Minutes = startOfDay until timeMinPlusOneDay by (WorkloadsHelpers.oneMinute * 15)
          //
          //            TestTimer.timeIt("grouped")(50) {
          //              val staffAtTIme = daysWorthOf15Minutes.map {
          //                time => (time) -> assignmentService.staffAt(time)
          //              }
          //            }
          //          }
          //
          //
          //          "asking for a whole days shape with movements of grouped staff" - {
          //            val assignmentService = MovementsShiftService(StaffAssignmentService.groupPeopleByShiftTimes(parsedAssignments.toList).toList)
          //            val startOfDay: Long = SDate(2016, 12, 1, 0, 0)
          //            val timeMinPlusOneDay: Long = startOfDay + WorkloadsHelpers.oneMinute * 60 * 36
          //            val daysWorthOf15Minutes = startOfDay until timeMinPlusOneDay by (WorkloadsHelpers.oneMinute * 15)
          //
          //            TestTimer.timeIt("movements")(1000) {
          //              val staffAtTIme = daysWorthOf15Minutes.map {
          //                time => (time) -> assignmentService.staffAt(time)
          //              }
          //            }
          //
          //          }

          "Staff movements" - {
            import StaffMovements._
            val shiftService = StaffAssignmentServiceWithoutDates(parsedShifts.toList).get
            val fixedPointService = StaffAssignmentServiceWithoutDates(parseRawTsv("").toList).get


            "Shifts can be represented as staff movements" - {
              val movements = (StaffMovement("T1", "IS81", SDate(2016, 12, 10, 10, 0), -2, UUID.randomUUID) :: Nil).sortBy(_.time)
              val sDate = SDate(2016, 12, 10, 10, 0)
              assert(staffAt(shiftService, fixedPointService)(movements)(sDate) == shiftService.staffAt(sDate) - 2)
            }

            "Movements from after the asked for date are not included" - {

              val movements = (StaffMovement("T1", "IS81", SDate(2016, 12, 10, 10, 0), -2, UUID.randomUUID) :: Nil).sortBy(_.time)
              val sDate = SDate(2016, 12, 10, 9, 0)
              assert(staffAt(shiftService, fixedPointService)(movements)(sDate) == shiftService.staffAt(sDate))
            }
            "Two movements at the same time are both taken into account" - {

              val movements = (
                StaffMovement("T1", "IS81", SDate(2016, 12, 11, 0, 0), -1, UUID.randomUUID) ::
                  StaffMovement("T1", "IS81", SDate(2016, 12, 11, 0, 0), -1, UUID.randomUUID) :: Nil
                ).sortBy(_.time)

              val sDate = SDate(2016, 12, 11, 0, 0)
              assert(staffAt(shiftService, fixedPointService)(movements)(sDate) == -2)

            }
            "Two movements at the same time as a shift entry are all taken into account" - {
              val shiftServiceWithOneShift = StaffAssignmentServiceWithoutDates(List(StaffAssignment("blah", "any", SDate(2016, 12, 11, 0, 0), SDate(2016, 12, 11, 1, 0), 10)))
              val movements = (
                StaffMovement("T1", "IS81", SDate(2016, 12, 11, 0, 0), -1, UUID.randomUUID) ::
                  StaffMovement("T1", "IS81", SDate(2016, 12, 11, 1, 0), 1, UUID.randomUUID) ::
                  StaffMovement("T1", "IS81", SDate(2016, 12, 11, 0, 0), -1, UUID.randomUUID) ::
                  StaffMovement("T1", "IS81", SDate(2016, 12, 11, 1, 0), 1, UUID.randomUUID) :: Nil
                ).sortBy(_.time)

              val sDate = SDate(2016, 12, 11, 0, 0)
              val staff: Int = staffAt(shiftServiceWithOneShift, fixedPointService)(movements)(sDate)
              assert(staff == 8)
            }
            "escaped commas are allowed in shift name" - {
              val shiftsRawCsv =
                """
                  |Alpha\, 1 ODM,any,01/12/16,06:30,15:18
                """.stripMargin
              val parsedShift: Try[StaffAssignment] = StaffAssignmentParser(shiftsRawCsv).parsedAssignments.head

              parsedShift match {
                case Success(StaffAssignment(name, _, _, _, _)) =>
                  assert(name == "Alpha\\, 1 ODM")
              }
            }
          }
          "Staff movements with fixed points" - {
            import StaffMovements._
            val shiftsRaw =
              """
                |Alpha,any,10/12/16,08:00,16:00,10
              """.stripMargin

            val fixedPointRaw =
              """
                |eGate Monitor,any,10/12/16,08:00,14:00,1
              """.stripMargin


            val shiftService = StaffAssignmentServiceWithoutDates(StaffAssignmentParser(shiftsRaw).parsedAssignments.toList).get
            val fixedPointService = StaffAssignmentServiceWithoutDates(StaffAssignmentParser(fixedPointRaw).parsedAssignments.toList).get


            "Fixed Points should be subracted from available staff" - {

              val sDate = SDate(2016, 12, 10, 10, 0)
              val result = staffAt(shiftService, fixedPointService)(Nil)(sDate)

              assert(result == 9)
            }
            "Fixed Points should only be included for the time specified" - {

              val sDate = SDate(2016, 12, 10, 15, 0)
              val result = staffAt(shiftService, fixedPointService)(Nil)(sDate)

              assert(result == 10)
            }
          }
          "Staff for a terminal should" - {
            import StaffMovements._
            val shiftsRaw =
              """
                |Alpha,T1,10/12/16,08:00,16:00,10
              """.stripMargin

            val fixedPointRaw =
              """
                |eGate Monitor,T1,10/12/16,08:00,14:00,1
              """.stripMargin


            val shiftService = StaffAssignmentServiceWithoutDates(StaffAssignmentParser(shiftsRaw).parsedAssignments.toList).get
            val fixedPointService = StaffAssignmentServiceWithoutDates(StaffAssignmentParser(fixedPointRaw).parsedAssignments.toList).get


            "Contain staff for a terminal shift" - {

              val sDate = SDate(2016, 12, 10, 10, 0)
              val result = terminalStaffAt( shiftService, fixedPointService)(Ready(Nil))("T1", sDate)

              assert(result == 9)
            }
//            "Fixed Points should only be included for the time specified" - {
//
//              val sDate = SDate(2016, 12, 10, 15, 0)
//              val result = staffAt(assignmentService, fixedPointService)(Nil)(sDate)
//
//              assert(result == 10)
//            }
          }
        }
      }
    }
  }

  def parseRawTsv(shiftsRawTsv: String): Array[Try[StaffAssignment]] = {
    val lines = shiftsRawTsv.split("\n")
    val parsedShifts = lines.map(l => l.split("\t"))
      .filter(_.length == 5)
      .map(pl => StaffAssignment(pl(0), pl(1), pl(2), pl(3), pl(4)))
    parsedShifts
  }
}

object TestTimer {
  def timeIt(name: String)(times: Int)(f: => Unit) = {
    val start = new Date()
    println(s"${name}: Starting timer at ${start}")
    (1 to times).foreach(n => {
      println(n)
      f
    })
    val end = new Date()
    println(s"${name} Trial done at ${end}")
    val timeTaken = (end.getTime() - start.getTime())
    println(s"${name} Time taken in ${times} runs ${timeTaken}ms, ${timeTaken.toDouble / times} per run")
  }
}
