package spatutorial.client.services

import diode.data.Ready
import spatutorial.client.components.DeskRecsChart
import spatutorial.client.modules.Dashboard.QueueCrunchResults
import spatutorial.client.services.JSDateConversions.SDate
import spatutorial.shared.{CrunchResult, MilliDate}
import spatutorial.shared.FlightsApi.TerminalName
import utest._
import spatutorial.client.logger._
import spatutorial.client.services.HandyStuff.QueueStaffDeployments

import scala.collection.immutable.{Map, Seq}
import scala.util.{Failure, Success, Try}

object StaffDeploymentCalculatorTests extends TestSuite {
  type TerminalQueueStaffDeployments = Map[TerminalName, QueueStaffDeployments]

//  object StaffDeployment {
//    def apply[M](rawShiftsString: String, terminalQueueCrunchResultsModel: Map[TerminalName, QueueCrunchResults], terminalName: TerminalName): Try[TerminalQueueStaffDeployments] = {
//      val shifts = ShiftParser(rawShiftsString).parsedShifts.toList //todo we have essentially this code elsewhere, look for successfulShifts
//      if (shifts.exists(s => s.isFailure)) {
//        log.error("Couldn't parse raw shifts")
//        Failure(new Exception("Couldn't parse"))
//      } else {
//        log.info(s"raw shifts are ${rawShiftsString}")
//        log.info(s"Shifts are ${shifts}")
//        val successfulShifts = shifts.collect { case Success(s) => s }
//        val ss = ShiftService(successfulShifts)
//        val staffAt = StaffMovements.staffAt(ss)(movements = Nil) _
//        val queueCrunchResults = terminalQueueCrunchResultsModel(terminalName)
//        val newUserDeskRecs = queueCrunchResults.mapValues(q => {
//          val queueDeskRecsOverTime = q.transpose {
//            case (_, Ready((_, Ready(DeskRecTimeSlots(items))))) => items
//          }
//
//          val deployments = queueDeskRecsOverTime.map((deskRecTimeSlots: Iterable[DeskRecTimeslot]) => {
//            val timeInMillis = MilliDate(deskRecTimeSlots.headOption.map(_.timeInMillis).getOrElse(0L))
//            queueRecsToDeployments(_.toInt)(deskRecTimeSlots.map(_.deskRec).toList, staffAt(timeInMillis))
//          }).transpose
//
//          val times: Seq[Long] = calculateDeskRecTimeSlots().map(drts => drts.timeInMillis)
//
//
//          val zipped = q.keys.zip({
//            deployments.map(times.zip(_).map { case (t, r) => DeskRecTimeslot(t, r) })
//          })
//
//          zipped.toMap.mapValues((x: Seq[DeskRecTimeslot]) => Ready(DeskRecTimeSlots(x)))
//        })
//      }
//    }
//
//    def calculateDeskRecTimeSlots(crunchResultWithTimeAndInterval: CrunchResult): DeskRecTimeSlots = {
//      val timeIntervalMinutes = 15
//      val millis = Iterator.iterate(crunchResultWithTimeAndInterval.firstTimeMillis)(_ + timeIntervalMinutes * crunchResultWithTimeAndInterval.intervalMillis).toIterable
//
//      val updatedDeskRecTimeSlots: DeskRecTimeSlots = DeskRecTimeSlots(
//        DeskRecsChart
//          .takeEveryNth(timeIntervalMinutes)(crunchResultWithTimeAndInterval.recommendedDesks)
//          .zip(millis).map {
//          case (deskRec, timeInMillis) => DeskRecTimeslot(timeInMillis = timeInMillis, deskRec = deskRec)
//        }.toList)
//      updatedDeskRecTimeSlots
//    }
//
//    def queueRecsToDeployments(round: Double => Int)(queueRecs: Seq[Int], staffAvailable: Int): Seq[Int] = {
//      val totalStaffRec = queueRecs.sum
//      queueRecs.foldLeft(List[Int]()) {
//        case (agg, queueRec) if (agg.length < queueRecs.length - 1) =>
//          agg :+ round(staffAvailable * (queueRec.toDouble / totalStaffRec))
//        case (agg, _) =>
//          agg :+ staffAvailable - agg.sum
//      }
//    }
//  }

    def tests = TestSuite {
      "Given terminal queue crunch results for one queue and staff shifts we should get suggested deployments for the queue" - {
        val shiftsRawCsv =
          """
            |Shift 1,01/12/16,00:00,01:00,6
          """.stripMargin

        val startTime = SDate(2016, 12, 1).millisSinceEpoch
        val oneHour = 60000
        val deskRecs = List.fill(60)(2).toIndexedSeq
        val waitTimes = List.fill(60)(10)
        val terminalQueueCrunchResults = Map(
          "T1" -> Map("eeaDesk" -> Ready(Ready(CrunchResult(startTime, oneHour, deskRecs, waitTimes))))
        )

        val result = StaffDeploymentCalculator(shiftsRawCsv, terminalQueueCrunchResults, "T1")
        val expected = Success(Map("T1" -> Map(
          "eeaDesk" -> Ready(DeskRecTimeSlots(
            List(
              DeskRecTimeslot(1480550400000L, 6),
              DeskRecTimeslot(1480551300000L, 6),
              DeskRecTimeslot(1480552200000L, 6),
              DeskRecTimeslot(1480553100000L, 6))
          ))
        )))

        assert(result == expected)
      }
      "Given terminal queue crunch results for two queues and staff shifts we should get suggested deployments for each queue" - {
        val shiftsRawCsv =
          """
            |Shift 1,01/12/16,00:00,01:00,6
          """.stripMargin

        val startTime = SDate(2016, 12, 1).millisSinceEpoch
        val oneHour = 60000
        val eeaDeskRecs = List.fill(60)(4).toIndexedSeq
        val nonEeaDeskRecs = List.fill(60)(2).toIndexedSeq
        val waitTimes = List.fill(60)(10)
        val terminalQueueCrunchResults = Map(
          "T1" -> Map(
            "eeaDesk" -> Ready(Ready(CrunchResult(startTime, oneHour, eeaDeskRecs, waitTimes))),
            "nonEea" -> Ready(Ready(CrunchResult(startTime, oneHour, nonEeaDeskRecs, waitTimes))))
        )

        val result = StaffDeploymentCalculator(shiftsRawCsv, terminalQueueCrunchResults, "T1")
        val expected = Success(Map("T1" -> Map(
          "eeaDesk" -> Ready(DeskRecTimeSlots(List(
            DeskRecTimeslot(1480550400000L, 4),
            DeskRecTimeslot(1480551300000L, 4),
            DeskRecTimeslot(1480552200000L, 4),
            DeskRecTimeslot(1480553100000L, 4)))),
          "nonEea" -> Ready(DeskRecTimeSlots(List(
            DeskRecTimeslot(1480550400000L, 2),
            DeskRecTimeslot(1480551300000L, 2),
            DeskRecTimeslot(1480552200000L, 2),
            DeskRecTimeslot(1480553100000L, 2))))
        )))
        println(s"suggested deployments::: $result")
        assert(result == expected)
      }
    }
  }
