package services.`export`

import actors.routing.minutes.MockMinutesLookup
import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, StaffMinute}
import drt.shared.{TM, TQM}
import services.SDate
import services.`export`.CsvTestHelper.{dropHeadings, resultStreamToCSV, takeCSVLines}
import services.crunch.CrunchTestLike
import services.exports.StreamingDesksExport
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDateLike

class StreamingDesksBstExportSpec extends CrunchTestLike {

  "When exporting DeskRecs during BST" >> {

    val firstHourPax = 17
    val secondHourPax = 16

    val eeaDeskRec = 5
    val nonEEADeskRec = 4
    val eGateRec = 3

    val eeaDeskDep = 4
    val nonEEADeskDep = 3
    val eGateDep = 2

    val waitTime = 8
    val workload = 6
    val depWait = 10
    val actDesk = 11
    val actWait = 12
    val minute1 = SDate("2021-04-05T23:00")
    val minute2 = SDate("2021-04-05T23:15")
    val minute3 = SDate("2021-04-05T23:30")
    val minute4 = SDate("2021-04-05T23:45")
    val minute5 = SDate("2021-04-06T00:00")
    val minute6 = SDate("2021-04-06T00:15")
    val minute7 = SDate("2021-04-06T00:30")
    val minute8 = SDate("2021-04-06T00:45")

    val crunchMinutesContainer = MinutesContainer[CrunchMinute, TQM](List(
      CrunchMinute(T1, Queues.EeaDesk, minute1.millisSinceEpoch, firstHourPax, workload, eeaDeskRec, waitTime, Option(eeaDeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EeaDesk, minute2.millisSinceEpoch, firstHourPax, workload, eeaDeskRec, waitTime, Option(eeaDeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EeaDesk, minute3.millisSinceEpoch, firstHourPax, workload, eeaDeskRec, waitTime, Option(eeaDeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EeaDesk, minute4.millisSinceEpoch, firstHourPax, workload, eeaDeskRec, waitTime, Option(eeaDeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EeaDesk, minute5.millisSinceEpoch, secondHourPax, workload, eeaDeskRec, waitTime, Option(eeaDeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EeaDesk, minute6.millisSinceEpoch, secondHourPax, workload, eeaDeskRec, waitTime, Option(eeaDeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EeaDesk, minute7.millisSinceEpoch, secondHourPax, workload, eeaDeskRec, waitTime, Option(eeaDeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EeaDesk, minute8.millisSinceEpoch, secondHourPax, workload, eeaDeskRec, waitTime, Option(eeaDeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.NonEeaDesk, minute1.millisSinceEpoch, firstHourPax, workload, nonEEADeskRec, waitTime, Option(nonEEADeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.NonEeaDesk, minute2.millisSinceEpoch, firstHourPax, workload, nonEEADeskRec, waitTime, Option(nonEEADeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.NonEeaDesk, minute3.millisSinceEpoch, firstHourPax, workload, nonEEADeskRec, waitTime, Option(nonEEADeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.NonEeaDesk, minute4.millisSinceEpoch, firstHourPax, workload, nonEEADeskRec, waitTime, Option(nonEEADeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.NonEeaDesk, minute5.millisSinceEpoch, secondHourPax, workload, nonEEADeskRec, waitTime, Option(nonEEADeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.NonEeaDesk, minute6.millisSinceEpoch, secondHourPax, workload, nonEEADeskRec, waitTime, Option(nonEEADeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.NonEeaDesk, minute7.millisSinceEpoch, secondHourPax, workload, nonEEADeskRec, waitTime, Option(nonEEADeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.NonEeaDesk, minute8.millisSinceEpoch, secondHourPax, workload, nonEEADeskRec, waitTime, Option(nonEEADeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EGate, minute1.millisSinceEpoch, firstHourPax, workload, eGateRec, waitTime, Option(eGateDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EGate, minute2.millisSinceEpoch, firstHourPax, workload, eGateRec, waitTime, Option(eGateDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EGate, minute3.millisSinceEpoch, firstHourPax, workload, eGateRec, waitTime, Option(eGateDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EGate, minute4.millisSinceEpoch, firstHourPax, workload, eGateRec, waitTime, Option(eGateDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EGate, minute5.millisSinceEpoch, secondHourPax, workload, eGateRec, waitTime, Option(eGateDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EGate, minute6.millisSinceEpoch, secondHourPax, workload, eGateRec, waitTime, Option(eGateDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EGate, minute7.millisSinceEpoch, secondHourPax, workload, eGateRec, waitTime, Option(eGateDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EGate, minute8.millisSinceEpoch, secondHourPax, workload, eGateRec, waitTime, Option(eGateDep), Option(depWait), Option(actDesk), Option(actWait)),
    ))

    val shifts = 1
    val misc = 2
    val moves = 1

    val staffMinutesContainer = MinutesContainer[StaffMinute, TM](List(
      StaffMinute(T1, minute1.millisSinceEpoch, shifts, misc, moves),
      StaffMinute(T1, minute2.millisSinceEpoch, shifts, misc, moves),
      StaffMinute(T1, minute3.millisSinceEpoch, shifts, misc, moves),
      StaffMinute(T1, minute4.millisSinceEpoch, shifts, misc, moves),
      StaffMinute(T1, minute5.millisSinceEpoch, shifts, misc, moves),
      StaffMinute(T1, minute6.millisSinceEpoch, shifts, misc, moves),
      StaffMinute(T1, minute7.millisSinceEpoch, shifts, misc, moves),
      StaffMinute(T1, minute8.millisSinceEpoch, shifts, misc, moves),
    ))


    "Given an export spanning 1 local date, I should get back the desk recs for that time period" >> {

      val exportStart: SDateLike = SDate("2021-04-06").getLocalLastMidnight
      val exportEnd: SDateLike = exportStart.getLocalNextMidnight.addMinutes(-1)

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
        exportStart,
        exportEnd,
        T1,
        defaultAirportConfig.forecastExportQueueOrder,
        crunchMinuteLookup,
        staffMinuteLookup
      )

      val result = takeCSVLines(dropHeadings(resultStreamToCSV(resultSource)), 8)

            val expected =
              s"""|2021-04-06,00:00,17,8,5,12,11,17,8,4,12,11,17,8,3,12,11,2,1,1,14
                  |2021-04-06,00:15,17,8,5,12,11,17,8,4,12,11,17,8,3,12,11,2,1,1,14
                  |2021-04-06,00:30,17,8,5,12,11,17,8,4,12,11,17,8,3,12,11,2,1,1,14
                  |2021-04-06,00:45,17,8,5,12,11,17,8,4,12,11,17,8,3,12,11,2,1,1,14
                  |2021-04-06,01:00,16,8,5,12,11,16,8,4,12,11,16,8,3,12,11,2,1,1,14
                  |2021-04-06,01:15,16,8,5,12,11,16,8,4,12,11,16,8,3,12,11,2,1,1,14
                  |2021-04-06,01:30,16,8,5,12,11,16,8,4,12,11,16,8,3,12,11,2,1,1,14
                  |2021-04-06,01:45,16,8,5,12,11,16,8,4,12,11,16,8,3,12,11,2,1,1,14"""
                .stripMargin


      result === expected
    }
  }
}
