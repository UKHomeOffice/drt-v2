package services.`export`

import actors.minutes.MockMinutesLookup
import actors.queues.DateRange
import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, StaffMinute}
import drt.shared.Terminals.T1
import drt.shared.{Queues, TM, TQM}
import services.SDate
import services.crunch.CrunchTestLike
import services.exports.StreamingDesksExport

import scala.concurrent.Await
import scala.concurrent.duration._

class StreamingDesksExportSpec extends CrunchTestLike {

  "Given CrunchMinutes and StaffMinutes on 1 day" >> {

    val pax = 5

    val eeaDeskRec = 5
    val nonEEADeskRec = 4
    val eGateRec = 3


    val eeaDeskDep = 4
    val nonEEADeskDep = 3
    val eGateDep = 2

    val waitTime = 8
    val depDesk = 9
    val depWait = 10
    val actDesk = 11
    val actWait = 12
    val minute1 = SDate("2020-11-19T00:00")
    val minute2 = SDate("2020-11-19T00:15")

    val crunchMinutesContainer = MinutesContainer[CrunchMinute, TQM](List(
      CrunchMinute(T1, Queues.EeaDesk, minute1.millisSinceEpoch, pax, 6, eeaDeskRec, waitTime, Option(eeaDeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EeaDesk, minute2.millisSinceEpoch, pax, 6, eeaDeskRec, waitTime, Option(eeaDeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.NonEeaDesk, minute1.millisSinceEpoch, pax, 6, nonEEADeskRec, waitTime, Option(nonEEADeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.NonEeaDesk, minute2.millisSinceEpoch, pax, 6, nonEEADeskRec, waitTime, Option(nonEEADeskDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EGate, minute1.millisSinceEpoch, pax, 6, eGateRec, waitTime, Option(eGateDep), Option(depWait), Option(actDesk), Option(actWait)),
      CrunchMinute(T1, Queues.EGate, minute2.millisSinceEpoch, pax, 6, eGateRec, waitTime, Option(eGateDep), Option(depWait), Option(actDesk), Option(actWait)),
    ))
    val shifts = 1

    val misc = 2
    val moves = 1

    val totalRec = eeaDeskRec + nonEEADeskRec + eGateRec + misc

    val staffMinutesContainer = MinutesContainer[StaffMinute, TM](List(
      StaffMinute(T1, minute1.millisSinceEpoch, shifts, misc, moves),
      StaffMinute(T1, minute2.millisSinceEpoch, shifts, misc, moves),
    ))

    val dates = DateRange.utcDateRangeSource(minute1, minute1)

    "When I ask for a desk recs CSV I should get back a stream of CSV strings matching those minutes" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsToCSVStream(
        dates,
        T1,
        defaultAirportConfig.exportQueueOrder,
        crunchMinuteLookup,
        staffMinuteLookup)

      val result = takeCSVLines(resultStreamToCSV(resultSource), 2)

      val expected =
        s"""|2020-11-19,00:00,${Math.round(pax)},$waitTime,$eeaDeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$nonEEADeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$eGateRec,${actWait},${actDesk},$misc,$moves,$shifts,$totalRec
            |2020-11-19,00:15,${Math.round(pax)},$waitTime,$eeaDeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$nonEEADeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$eGateRec,${actWait},${actDesk},$misc,$moves,$shifts,$totalRec"""
          .stripMargin

      result === expected
    }

    "When I ask for a deployments recs CSV I should get back a stream of CSV strings matching those minutes" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deploymentsToCSVStream(
        dates,
        T1,
        defaultAirportConfig.exportQueueOrder,
        crunchMinuteLookup,
        staffMinuteLookup)

      val result = takeCSVLines(resultStreamToCSV(resultSource), 2)

      val expected =
        s"""|2020-11-19,00:00,${Math.round(pax)},$depWait,$eeaDeskDep,${actWait},${actDesk},${Math.round(pax)},$depWait,$nonEEADeskDep,${actWait},${actDesk},${Math.round(pax)},$depWait,$eGateDep,${actWait},${actDesk},$misc,$moves,$shifts,$totalRec
            |2020-11-19,00:15,${Math.round(pax)},$depWait,$eeaDeskDep,${actWait},${actDesk},${Math.round(pax)},$depWait,$nonEEADeskDep,${actWait},${actDesk},${Math.round(pax)},$depWait,$eGateDep,${actWait},${actDesk},$misc,$moves,$shifts,$totalRec"""
          .stripMargin

      result === expected
    }

    "When I ask for a desk recs CSV with headings I should get back a stream of CSV strings matching those minutes with headings" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
        dates,
        T1,
        defaultAirportConfig.exportQueueOrder,
        crunchMinuteLookup,
        staffMinuteLookup)

      val result = takeCSVLines(resultStreamToCSV(resultSource), 4)

      val expected =
        s"""|Date,,EEA,EEA,EEA,EEA,EEA,Non-EEA,Non-EEA,Non-EEA,Non-EEA,Non-EEA,e-Gates,e-Gates,e-Gates,e-Gates,e-Gates,Misc,Moves,PCP Staff,PCP Staff
            |,Start,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Staff req,Act. wait time,Act. desks,Staff req,Staff movements,Avail,Req
            |2020-11-19,00:00,${Math.round(pax)},$waitTime,$eeaDeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$nonEEADeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$eGateRec,${actWait},${actDesk},$misc,$moves,$shifts,$totalRec
            |2020-11-19,00:15,${Math.round(pax)},$waitTime,$eeaDeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$nonEEADeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$eGateRec,${actWait},${actDesk},$misc,$moves,$shifts,$totalRec"""
          .stripMargin

      result === expected
    }

    "When I ask for a desk deps CSV with headings I should get back a stream of CSV strings matching those minutes with headings" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deploymentsToCSVStreamWithHeaders(
        dates,
        T1,
        defaultAirportConfig.exportQueueOrder,
        crunchMinuteLookup,
        staffMinuteLookup)

      val result = takeCSVLines(resultStreamToCSV(resultSource), 4)

      val expected =
        s"""|Date,,EEA,EEA,EEA,EEA,EEA,Non-EEA,Non-EEA,Non-EEA,Non-EEA,Non-EEA,e-Gates,e-Gates,e-Gates,e-Gates,e-Gates,Misc,Moves,PCP Staff,PCP Staff
            |,Start,Pax,Wait,Desks dep,Act. wait time,Act. desks,Pax,Wait,Desks dep,Act. wait time,Act. desks,Pax,Wait,Staff dep,Act. wait time,Act. desks,Staff req,Staff movements,Avail,Req
            |2020-11-19,00:00,${Math.round(pax)},$depWait,$eeaDeskDep,${actWait},${actDesk},${Math.round(pax)},$depWait,$nonEEADeskDep,${actWait},${actDesk},${Math.round(pax)},$depWait,$eGateDep,${actWait},${actDesk},$misc,$moves,$shifts,$totalRec
            |2020-11-19,00:15,${Math.round(pax)},$depWait,$eeaDeskDep,${actWait},${actDesk},${Math.round(pax)},$depWait,$nonEEADeskDep,${actWait},${actDesk},${Math.round(pax)},$depWait,$eGateDep,${actWait},${actDesk},$misc,$moves,$shifts,$totalRec"""
          .stripMargin

      result === expected
    }

    "When I ask for a desk recs CSV I should get back a stream of CSV strings matching those minutes" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsToCSVStream(
        dates,
        T1,
        defaultAirportConfig.exportQueueOrder,
        crunchMinuteLookup,
        staffMinuteLookup)

      val result = takeCSVLines(resultStreamToCSV(resultSource), 2)

      val expected =
        s"""|2020-11-19,00:00,${Math.round(pax)},$waitTime,$eeaDeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$nonEEADeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$eGateRec,${actWait},${actDesk},$misc,$moves,$shifts,$totalRec
            |2020-11-19,00:15,${Math.round(pax)},$waitTime,$eeaDeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$nonEEADeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$eGateRec,${actWait},${actDesk},$misc,$moves,$shifts,$totalRec"""
          .stripMargin

      result === expected
    }

    "When I ask for a desk recs CSV I should get a line for every time slot in the period plus 2 for headings" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val dates = DateRange.utcDateRangeSource(SDate("2020-11-01"), SDate("2020-11-03"))

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
        dates,
        T1,
        defaultAirportConfig.exportQueueOrder,
        crunchMinuteLookup,
        staffMinuteLookup)

      val result = resultStreamToCSV(resultSource).split("\n").length

      val timeSlotsPerDay = 96
      val numberOfDays = 3
      val headingRows = 2

      val expected = (timeSlotsPerDay * numberOfDays) + headingRows

      result === expected
    }

    "When I ask for a desk recs CSV spanning 2 days 23:00 timeslots should be followed by 00:00 timeslots" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val dates = DateRange.utcDateRangeSource(SDate("2020-11-01"), SDate("2020-11-02"))

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
        dates,
        T1,
        defaultAirportConfig.exportQueueOrder,
        crunchMinuteLookup,
        staffMinuteLookup)

      val headingLines = 2

      val timeSlotsPerDay = 96

      val result = resultStreamToCSV(resultSource).split("\n")
        .drop(headingLines)
        .slice(timeSlotsPerDay - 1, timeSlotsPerDay + 1)
        .mkString("\n")

      val expected =
        """|2020-11-01,23:45,0,0,0,,,0,0,0,,,0,0,0,,,0,0,0,0
           |2020-11-02,00:00,0,0,0,,,0,0,0,,,0,0,0,,,0,0,0,0""".stripMargin

      result === expected
    }

    "Given CrunchMinutes and no StaffMinutes on 1 day I should get back empty values for staff minutes" >> {

      val staffMinutesContainer = MinutesContainer[StaffMinute, TM](List())

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val dates = DateRange.utcDateRangeSource(minute1, minute1)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsToCSVStream(
        dates,
        T1,
        defaultAirportConfig.exportQueueOrder,
        crunchMinuteLookup,
        staffMinuteLookup)

      val result = takeCSVLines(resultStreamToCSV(resultSource), 2)

      val expected =
        s"""|2020-11-19,00:00,${Math.round(pax)},$waitTime,$eeaDeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$nonEEADeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$eGateRec,${actWait},${actDesk},0,0,0,12
            |2020-11-19,00:15,${Math.round(pax)},$waitTime,$eeaDeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$nonEEADeskRec,${actWait},${actDesk},${Math.round(pax)},$waitTime,$eGateRec,${actWait},${actDesk},0,0,0,12"""
          .stripMargin

      result === expected
    }
  }


  private def takeCSVLines(csvResult: String, linesToTake: Int) = {
    csvResult
      .split("\n")
      .take(linesToTake)
      .mkString("\n")
  }

  def resultStreamToCSV(resultSource: Source[String, NotUsed]) = {
    Await.result(resultSource.runWith(Sink.seq), 1 second).mkString
  }
}
