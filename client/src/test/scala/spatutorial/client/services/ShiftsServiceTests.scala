package spatutorial.client.services

import java.util.UUID

import spatutorial.client.services.JSDateConversions.SDate.JSSDate
import spatutorial.shared.{SDate, StaffMovement}
import utest._

import scala.scalajs.js.Date
import scala.util.{Failure, Success, Try}

object ShiftsServiceTests extends TestSuite {

  import spatutorial.client.services.JSDateConversions._

  def tests = TestSuite {
    'StaffShifts - {
      "As an HO, either in planning or at start of shift, " +
        "I want to be able tell DRT about staff available by shift for a given period" +
        "So that I can easily get an initial state for the system" - {

        "some implicits make things nicer whether we're server side or client side " - {
          val startDt = SDate(2016, 12, 10, 10, 0)
          val endDate = SDate(2016, 12, 19, 12, 0)
          val shifts = Shift("alpha", "any", startDt, endDate, 10)

          assert(shifts == Shift("alpha", "any", 1481364000000L, 1482148800000L, 10))
        }
        "round trip the above magic numbers 1481364000000d is 2016/12/10 10:00" - {
          val sdate: SDate = SDate.JSSDate(new Date(1481364000000d))
          assert((2016, 12, 10, 10, 0) == (sdate.getFullYear(), sdate.getMonth(), sdate.getDate(), sdate.getHours(), sdate.getMinutes()))
        }

        "round trip the above magic numbers 1482148800000L is 2016/12/19 12:00" - {
          val sdate: SDate = SDate.JSSDate(new Date(1482148800000d))
          assert((2016, 12, 19, 12, 0) == (sdate.getFullYear(), sdate.getMonth(), sdate.getDate(), sdate.getHours(), sdate.getMinutes()))
        }

        "Given a shift of 10 people, if we ask how many staff are available" - {
          val shifts = Shift("alpha", "any", SDate(2016, 12, 10, 10, 0), SDate(2016, 12, 10, 19, 0), 10)
          val shiftService = ShiftService(shifts :: Nil)
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

        "Given two overlapping shifts" - {
          val shifts = Shift("alpha", "any", SDate(2016, 12, 10, 10, 0), SDate(2016, 12, 10, 19, 0), 10) ::
            Shift("beta", "any", SDate(2016, 12, 10, 18, 0), SDate(2016, 12, 10, 23, 0), 5) :: Nil
          val shiftService = ShiftService(shifts)
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

        "Shift parsing " - {
          "Parse a single shift line" - {
            val shiftsRawCsv =
              """
                |Shift 1,any,01/12/16,06:30,15:18,2
              """.stripMargin


            val shifts = ShiftParser(shiftsRawCsv).parsedShifts.toList
            assert(shifts == Success(Shift("Shift 1", "any", SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), 2)) :: Nil)
          }

          " Parse a couple of shift lines" - {
            val shiftsRawCsv =
              """
                |Shift 1,any,01/12/16,06:30,15:18,2
                |Shift 2,any,01/12/16,19:00,22:24,4
              """.stripMargin


            val shifts = ShiftParser(shiftsRawCsv).parsedShifts.toList
            val expectedShifts = Success(Shift("Shift 1", "any", SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), 2)) ::
              Success(Shift("Shift 2", "any", SDate(2016, 12, 1, 19, 0), SDate(2016, 12, 1, 22, 24), 4)) ::
              Nil
            assert(shifts == expectedShifts
            )
          }

          "-ve numbers are fine in movements" - {
            val shiftsRawCsv =
              """
                |Shift 1,any,01/12/16,06:30,15:18,-2
              """.stripMargin


            val shifts = ShiftParser(shiftsRawCsv).parsedShifts.toList
            val expectedShifts = Success(Shift("Shift 1", "any", SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), -2)) :: Nil
            assert(shifts == expectedShifts)
          }

          "We can make comments by not using commas" - {
            val shiftsRawCsv =
              """
                |# here be a comment - not because of the hash but because no commas
                |Shift 1,any,01/12/16,06:30,15:18,-2
              """.stripMargin


            val shifts = ShiftParser(shiftsRawCsv).parsedShifts.toList
            val expectedShifts = Success(Shift("Shift 1", "any", SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), -2)) :: Nil
            assert(shifts == expectedShifts)
          }

          "empty lines are ignored" - {
            val shiftsRawCsv =
              """
                |
                |Shift 1,any,01/12/16,06:30,15:18,-2
              """.stripMargin


            val shifts = ShiftParser(shiftsRawCsv).parsedShifts.toList
            val expectedShifts = Success(Shift("Shift 1", "any", SDate(2016, 12, 1, 6, 30), SDate(2016, 12, 1, 15, 18), -2)) :: Nil
            assert(shifts == expectedShifts)
          }

          "failure to read a date will return an error for that line" - {
            val shiftsRawCsv =
              """
                |Bad line,any,01/1b/16,06:30,15:18,-2
              """.stripMargin


            val shifts = ShiftParser(shiftsRawCsv).parsedShifts.toList
            shifts match {
              case Failure(t) :: Nil => assert(true)
              case other =>
                println(s"Should have failed that bad line ${other}")
                assert(false)
            }
          }
        }

        "Shift to csv string representation" - {
          "Given a shift when I ask for a csv string then I should get a string with the fields separated by commas" - {
            val shiftTry: Try[Shift] = Shift("My shift", "any", "01/01/17", "08:00", "11:59", "2")
            val shift = shiftTry.get

            val csvString = shift.toCsv

            val expected = "My shift,any,01/01/17,08:00,11:59,2"

            assert(csvString == expected)
          }
        }

        "Given all the shifts, how fast is it?" - {
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

          val parsedShifts: Array[Try[Shift]] = parseRawTsv(shiftsRawTsv)

          println(parsedShifts.mkString("\n"))

          // Commented out some performance tests we used to evaluate different approaches.

          //          "asking for a whole days shape with individual stuff" - {
          //            val shiftService = ShiftService(parsedShifts.toList)
          //            val startOfDay: Long = SDate(2016, 12, 1, 0, 0)
          //            val timeMinPlusOneDay: Long = startOfDay + WorkloadsHelpers.oneMinute * 60 * 36
          //            val daysWorthOf15Minutes = startOfDay until timeMinPlusOneDay by (WorkloadsHelpers.oneMinute * 15)
          //
          //            TestTimer.timeIt("individuals")(50) {
          //              val staffAtTIme = daysWorthOf15Minutes.map {
          //                time => (time) -> shiftService.staffAt(time)
          //              }
          //            }
          //
          //          }

          //          "asking for a whole days shape with grouped staff" - {
          //            val shiftService = ShiftService(ShiftService.groupPeopleByShiftTimes(parsedShifts).toList)
          //            val startOfDay: Long = SDate(2016, 12, 1, 0, 0)
          //            val timeMinPlusOneDay: Long = startOfDay + WorkloadsHelpers.oneMinute * 60 * 36
          //            val daysWorthOf15Minutes = startOfDay until timeMinPlusOneDay by (WorkloadsHelpers.oneMinute * 15)
          //
          //            TestTimer.timeIt("grouped")(50) {
          //              val staffAtTIme = daysWorthOf15Minutes.map {
          //                time => (time) -> shiftService.staffAt(time)
          //              }
          //            }
          //          }
          //
          //
          //          "asking for a whole days shape with movements of grouped staff" - {
          //            val shiftService = MovementsShiftService(ShiftService.groupPeopleByShiftTimes(parsedShifts.toList).toList)
          //            val startOfDay: Long = SDate(2016, 12, 1, 0, 0)
          //            val timeMinPlusOneDay: Long = startOfDay + WorkloadsHelpers.oneMinute * 60 * 36
          //            val daysWorthOf15Minutes = startOfDay until timeMinPlusOneDay by (WorkloadsHelpers.oneMinute * 15)
          //
          //            TestTimer.timeIt("movements")(1000) {
          //              val staffAtTIme = daysWorthOf15Minutes.map {
          //                time => (time) -> shiftService.staffAt(time)
          //              }
          //            }
          //
          //          }

          "Staff movements" - {
            import StaffMovements._
            val shiftServiceTry = ShiftService(parsedShifts.toList)

            assert(shiftServiceTry.isSuccess)

            "Shifts can be represented as staff movements" - {
              shiftServiceTry match {
                case Success(shiftService) =>
                  val movements = (StaffMovement("IS81", SDate(2016, 12, 10, 10, 0), -2, UUID.randomUUID) :: Nil).sortBy(_.time)

                  val sDate = SDate(2016, 12, 10, 10, 0)
                  assert(staffAt(shiftService)(movements)(sDate) == shiftService.staffAt(sDate) - 2)
              }
            }
            "Movements from after the asked for date are not included" - {
              shiftServiceTry match {
                case Success(shiftService) =>
                  val movements = (StaffMovement("IS81", SDate(2016, 12, 10, 10, 0), -2, UUID.randomUUID) :: Nil).sortBy(_.time)

                  val sDate = SDate(2016, 12, 10, 9, 0)
                  assert(staffAt(shiftService)(movements)(sDate) == shiftService.staffAt(sDate))
              }
            }
            "Two movements at the same time are both taken into account" - {
              shiftServiceTry match {
                case Success(shiftService) =>
                  val movements = (
                    StaffMovement("IS81", SDate(2016, 12, 11, 0, 0), -1, UUID.randomUUID) ::
                      StaffMovement("IS81", SDate(2016, 12, 11, 0, 0), -1, UUID.randomUUID) :: Nil
                    ).sortBy(_.time)

                  val sDate = SDate(2016, 12, 11, 0, 0)
                  assert(staffAt(shiftService)(movements)(sDate) == -2)
              }
            }
            "Two movements at the same time as a shift entry are all taken into account" - {
              val shiftServiceWithOneShift = ShiftService(List(Shift("blah", "any", SDate(2016, 12, 11, 0, 0), SDate(2016, 12, 11, 1, 0), 10)))
              val movements = (
                StaffMovement("IS81", SDate(2016, 12, 11, 0, 0), -1, UUID.randomUUID) ::
                  StaffMovement("IS81", SDate(2016, 12, 11, 1, 0), 1, UUID.randomUUID) ::
                  StaffMovement("IS81", SDate(2016, 12, 11, 0, 0), -1, UUID.randomUUID) ::
                  StaffMovement("IS81", SDate(2016, 12, 11, 1, 0), 1, UUID.randomUUID) :: Nil
                ).sortBy(_.time)

              val sDate = SDate(2016, 12, 11, 0, 0)
              val staff: Int = staffAt(shiftServiceWithOneShift)(movements)(sDate)
              assert(staff == 8)
            }
            "escaped commas are allowed in shift name" - {
              val shiftsRawCsv =
                """
                  |Alpha\, 1 ODM,any,01/12/16,06:30,15:18
                """.stripMargin
              val parsedShift: Try[Shift] = ShiftParser(shiftsRawCsv).parsedShifts.head

              parsedShift match {
                case Success(Shift(name, _, _, _, _)) =>
                  assert(name == "Alpha\\, 1 ODM")
              }
            }
          }
        }
      }
    }

    'TerminalStaffShifts - {
      "Given a shift of 10 people at T1, if we ask how many staff are available" - {
        val shifts = Shift("alpha", "T1", SDate(2016, 12, 10, 10, 0), SDate(2016, 12, 10, 19, 0), 10)
        val shiftService = ShiftService(shifts :: Nil)

        "at T1 at its first bound, then we get 10" - {
          assert(shiftService.terminalStaffAt("T1", SDate(2016, 12, 10, 10, 0)) == 10)
        }

        "at T2 at its first bound, then we get 0" - {
          assert(shiftService.terminalStaffAt("T2", SDate(2016, 12, 10, 10, 0)) == 0)
        }

//        "at its upper bound, then we get 10" - {
//          assert(shiftService.terminalStaffAt(SDate(2016, 12, 10, 19, 0)) == 10)
//        }
//
//        "can compare dates" - {
//          assert(SDate(2015, 10, 10, 10, 10) < SDate(2016, 12, 12, 12, 12))
//          assert(SDate(2015, 10, 10, 10, 10) <= SDate(2016, 12, 12, 12, 12))
//        }
      }
    }
  }

  def parseRawTsv(shiftsRawTsv: String): Array[Try[Shift]] = {
    val lines = shiftsRawTsv.split("\n")
    val parsedShifts = lines.map(l => l.split("\t"))
      .filter(_.length == 5)
      .map(pl => Shift(pl(0), pl(1), pl(2), pl(3), pl(4)))
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
