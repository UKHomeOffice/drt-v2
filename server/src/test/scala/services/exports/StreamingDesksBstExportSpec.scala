package services.exports

import actors.routing.minutes.MockMinutesLookup
import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, StaffMinute}
import drt.shared.{TM, TQM}
import uk.gov.homeoffice.drt.time.SDate
import services.exports.CsvTestHelper.{dropHeadings, resultStreamToCSV, takeCSVLines}
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk}
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

    val crunchMinutesContainer = MinutesContainer[CrunchMinute, TQM](
      for {
        queue <- List(EeaDesk, NonEeaDesk, EGate)
        minute <- List(minute1, minute2, minute3, minute4, minute5, minute6, minute7, minute8)
      } yield CrunchMinute(
        terminal = T1,
        queue = queue,
        minute = minute.millisSinceEpoch,
        paxLoad = if (minute.getDate == 5) firstHourPax else secondHourPax,
        workLoad = workload,
        deskRec = Map(EeaDesk -> eeaDeskRec, NonEeaDesk -> nonEEADeskRec, EGate -> eGateRec)(queue),
        waitTime = waitTime,
        maybePaxInQueue = None,
        deployedDesks = Option(Map(EeaDesk -> eeaDeskDep, NonEeaDesk -> nonEEADeskDep, EGate -> eGateDep)(queue)),
        deployedWait = Option(depWait),
        maybeDeployedPaxInQueue = None,
        actDesks = Option(actDesk),
        actWait = Option(actWait)
      )
    )

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
