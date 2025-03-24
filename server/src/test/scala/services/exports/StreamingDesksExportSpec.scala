package services.exports

import actors.routing.minutes.MockMinutesLookup
import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.{MinutesContainer, StaffMinute}
import drt.shared.TM
import services.crunch.CrunchTestLike
import services.exports.CsvTestHelper._
import uk.gov.homeoffice.drt.model.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import uk.gov.homeoffice.drt.time.SDate

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
    val workload = 6
    val depWait = 10
    val actDesk = 11
    val actWait = 12
    val minute1 = SDate("2020-11-19T00:00")
    val minute2 = SDate("2020-11-19T00:15")

    val crunchMinutesContainer = MinutesContainer[CrunchMinute, TQM](for {
      queue <- List(EeaDesk, NonEeaDesk, EGate)
      minute <- List(minute1, minute2)
    } yield CrunchMinute(
      terminal = T1,
      queue = queue,
      minute = minute.millisSinceEpoch,
      paxLoad = pax,
      workLoad = workload,
      deskRec = Map(EeaDesk -> eeaDeskRec, NonEeaDesk -> nonEEADeskRec, EGate -> eGateRec)(queue),
      waitTime = waitTime,
      maybePaxInQueue = None,
      deployedDesks = Option(Map(EeaDesk -> eeaDeskDep, NonEeaDesk -> nonEEADeskDep, EGate -> eGateDep)(queue)),
      deployedWait = Option(depWait),
      maybeDeployedPaxInQueue = None,
      actDesks = Option(actDesk),
      actWait = Option(actWait),
    ))

    val shifts = 1
    val misc = 2
    val moves = 1

    val totalRec = eeaDeskRec + nonEEADeskRec + eGateRec + misc

    val staffMinutesContainer = MinutesContainer[StaffMinute, TM](List(
      StaffMinute(T1, minute1.millisSinceEpoch, shifts, misc, moves),
      StaffMinute(T1, minute2.millisSinceEpoch, shifts, misc, moves),
    ))


    "When I ask for a desk recs CSV I should get back a stream of CSV strings matching those minutes" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
        start = minute1,
        end = minute2,
        terminal = T1,
        exportQueuesInOrder = defaultAirportConfig.forecastExportQueueOrder,
        crunchMinuteLookup = crunchMinuteLookup,
        staffMinuteLookup = staffMinuteLookup,
        maybePit = None,
        periodMinutes = 15,
      )

      val result = takeCSVLines(dropHeadings(resultStreamToCSV(resultSource)), 2)

      val expected =
        s"""|2020-11-19,${T1.toString},00:00,$pax,$waitTime,$eeaDeskRec,$actWait,$actDesk,$pax,$waitTime,$nonEEADeskRec,$actWait,$actDesk,$pax,$waitTime,$eGateRec,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec
            |2020-11-19,${T1.toString},00:15,$pax,$waitTime,$eeaDeskRec,$actWait,$actDesk,$pax,$waitTime,$nonEEADeskRec,$actWait,$actDesk,$pax,$waitTime,$eGateRec,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec"""
          .stripMargin

      result === expected
    }

    "When I ask for a deployments recs CSV I should get back a stream of CSV strings matching those minutes" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deploymentsToCSVStreamWithHeaders(
        start = minute1,
        end = minute2,
        terminal = T1,
        exportQueuesInOrder = defaultAirportConfig.forecastExportQueueOrder,
        crunchMinuteLookup = crunchMinuteLookup,
        staffMinuteLookup = staffMinuteLookup,
        maybePit = None,
        periodMinutes = 15,
      )

      val result = takeCSVLines(dropHeadings(resultStreamToCSV(resultSource)), 2)

      val expected =
        s"""|2020-11-19,${T1.toString},00:00,$pax,$depWait,$eeaDeskDep,$actWait,$actDesk,$pax,$depWait,$nonEEADeskDep,$actWait,$actDesk,$pax,$depWait,$eGateDep,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec
            |2020-11-19,${T1.toString},00:15,$pax,$depWait,$eeaDeskDep,$actWait,$actDesk,$pax,$depWait,$nonEEADeskDep,$actWait,$actDesk,$pax,$depWait,$eGateDep,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec"""
          .stripMargin

      result === expected
    }

    "When I ask for a desk recs CSV with headings I should get back a stream of CSV strings matching those minutes with headings" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
        minute1,
        minute2,
        T1,
        defaultAirportConfig.forecastExportQueueOrder,
        crunchMinuteLookup,
        staffMinuteLookup,
        maybePit = None,
        periodMinutes = 15,
      )

      val result = takeCSVLines(resultStreamToCSV(resultSource), 4)

      val expected =
        s"""|Date,Terminal,,EEA,EEA,EEA,EEA,EEA,Non-EEA,Non-EEA,Non-EEA,Non-EEA,Non-EEA,e-Gates,e-Gates,e-Gates,e-Gates,e-Gates,Misc,Moves,PCP Staff,PCP Staff
            |,,Start,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Staff req,Act. wait time,Act. desks,Staff req,Staff movements,Avail,Req
            |2020-11-19,${T1.toString},00:00,$pax,$waitTime,$eeaDeskRec,$actWait,$actDesk,$pax,$waitTime,$nonEEADeskRec,$actWait,$actDesk,$pax,$waitTime,$eGateRec,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec
            |2020-11-19,${T1.toString},00:15,$pax,$waitTime,$eeaDeskRec,$actWait,$actDesk,$pax,$waitTime,$nonEEADeskRec,$actWait,$actDesk,$pax,$waitTime,$eGateRec,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec"""
          .stripMargin

      result === expected
    }

    "When I ask for a desk deps CSV with headings I should get back a stream of CSV strings matching those minutes with headings" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deploymentsToCSVStreamWithHeaders(
        start = minute1,
        end = minute2,
        terminal = T1,
        exportQueuesInOrder = defaultAirportConfig.forecastExportQueueOrder,
        crunchMinuteLookup = crunchMinuteLookup,
        staffMinuteLookup = staffMinuteLookup,
        maybePit = None,
        periodMinutes = 15,
      )

      val result = takeCSVLines(resultStreamToCSV(resultSource), 4)

      val expected =
        s"""|Date,Terminal,,EEA,EEA,EEA,EEA,EEA,Non-EEA,Non-EEA,Non-EEA,Non-EEA,Non-EEA,e-Gates,e-Gates,e-Gates,e-Gates,e-Gates,Misc,Moves,PCP Staff,PCP Staff
            |,,Start,Pax,Wait,Desks dep,Act. wait time,Act. desks,Pax,Wait,Desks dep,Act. wait time,Act. desks,Pax,Wait,Staff dep,Act. wait time,Act. desks,Staff req,Staff movements,Avail,Req
            |2020-11-19,${T1.toString},00:00,$pax,$depWait,$eeaDeskDep,$actWait,$actDesk,$pax,$depWait,$nonEEADeskDep,$actWait,$actDesk,$pax,$depWait,$eGateDep,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec
            |2020-11-19,${T1.toString},00:15,$pax,$depWait,$eeaDeskDep,$actWait,$actDesk,$pax,$depWait,$nonEEADeskDep,$actWait,$actDesk,$pax,$depWait,$eGateDep,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec"""
          .stripMargin

      result === expected
    }

    "When I ask for a desk recs CSV I should get back a stream of CSV strings matching those minutes" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
        start = minute1,
        end = minute2,
        terminal = T1,
        exportQueuesInOrder = defaultAirportConfig.forecastExportQueueOrder,
        crunchMinuteLookup = crunchMinuteLookup,
        staffMinuteLookup = staffMinuteLookup,
        maybePit = None,
        periodMinutes = 15,
      )

      val result = takeCSVLines(dropHeadings(resultStreamToCSV(resultSource)), 2)

      val expected =
        s"""|2020-11-19,${T1.toString},00:00,$pax,$waitTime,$eeaDeskRec,$actWait,$actDesk,$pax,$waitTime,$nonEEADeskRec,$actWait,$actDesk,$pax,$waitTime,$eGateRec,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec
            |2020-11-19,${T1.toString},00:15,$pax,$waitTime,$eeaDeskRec,$actWait,$actDesk,$pax,$waitTime,$nonEEADeskRec,$actWait,$actDesk,$pax,$waitTime,$eGateRec,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec"""
          .stripMargin

      result === expected
    }

    "When I ask for a desk recs CSV I should get a line for every time slot in the period plus 2 for headings" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val start = SDate("2020-11-01")
      val end = SDate("2020-11-03").getLocalNextMidnight.addMinutes(-1)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
        start = start,
        end = end,
        terminal = T1,
        exportQueuesInOrder = defaultAirportConfig.forecastExportQueueOrder,
        crunchMinuteLookup = crunchMinuteLookup,
        staffMinuteLookup = staffMinuteLookup,
        maybePit = None,
        periodMinutes = 15,
      )

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

      val start = SDate("2020-11-01")
      val end = SDate("2020-11-02")

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
        start = start,
        end = end,
        terminal = T1,
        exportQueuesInOrder = defaultAirportConfig.forecastExportQueueOrder,
        crunchMinuteLookup = crunchMinuteLookup,
        staffMinuteLookup = staffMinuteLookup,
        maybePit = None,
        periodMinutes = 15,
      )

      val headingLines = 2

      val timeSlotsPerDay = 96

      val result = resultStreamToCSV(resultSource).split("\n")
        .drop(headingLines)
        .slice(timeSlotsPerDay - 1, timeSlotsPerDay + 1)
        .mkString("\n")

      val expected =
        """|2020-11-01,T1,23:45,0,0,0,,,0,0,0,,,0,0,0,,,0,0,0,0
           |2020-11-02,T1,00:00,0,0,0,,,0,0,0,,,0,0,0,,,0,0,0,0""".stripMargin

      result === expected
    }

    "Given CrunchMinutes and no StaffMinutes on 1 day I should get back empty values for staff minutes" >> {

      val staffMinutesContainer = MinutesContainer[StaffMinute, TM](List())

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)


      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
        start = minute1,
        end = minute2,
        terminal = T1,
        exportQueuesInOrder = defaultAirportConfig.forecastExportQueueOrder,
        crunchMinuteLookup = crunchMinuteLookup,
        staffMinuteLookup = staffMinuteLookup,
        maybePit = None,
        periodMinutes = 15,
      )

      val result = takeCSVLines(dropHeadings(resultStreamToCSV(resultSource)), 2)

      val expected =
        s"""|2020-11-19,${T1.toString},00:00,$pax,$waitTime,$eeaDeskRec,$actWait,$actDesk,$pax,$waitTime,$nonEEADeskRec,$actWait,$actDesk,$pax,$waitTime,$eGateRec,$actWait,$actDesk,0,0,0,12
            |2020-11-19,${T1.toString},00:15,$pax,$waitTime,$eeaDeskRec,$actWait,$actDesk,$pax,$waitTime,$nonEEADeskRec,$actWait,$actDesk,$pax,$waitTime,$eGateRec,$actWait,$actDesk,0,0,0,12"""
          .stripMargin

      result === expected
    }
  }

  "Given CrunchMinutes and StaffMinutes for multiple terminals on 1 day" >> {

    val pax = 5

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
    val minute1 = SDate("2020-11-19T00:00")
    val minute2 = SDate("2020-11-19T00:15")

    val crunchMinutesContainer = MinutesContainer[CrunchMinute, TQM](for {
      terminal <- List(T1, T2)
      queue <- List(EeaDesk, NonEeaDesk, EGate)
      minute <- List(minute1, minute2)
    } yield CrunchMinute(
      terminal = terminal,
      queue = queue,
      minute = minute.millisSinceEpoch,
      paxLoad = pax,
      workLoad = workload,
      deskRec = Map(EeaDesk -> eeaDeskRec, NonEeaDesk -> nonEEADeskRec, EGate -> eGateRec)(queue),
      waitTime = waitTime,
      maybePaxInQueue = None,
      deployedDesks = Option(Map(EeaDesk -> eeaDeskDep, NonEeaDesk -> nonEEADeskDep, EGate -> eGateDep)(queue)),
      deployedWait = Option(depWait),
      maybeDeployedPaxInQueue = None,
      actDesks = Option(actDesk),
      actWait = Option(actWait),
    ))

    val shifts = 1
    val misc = 2
    val moves = 1

    val totalRec = eeaDeskRec + nonEEADeskRec + eGateRec + misc

    val staffMinutesContainer = MinutesContainer[StaffMinute, TM](for {
      terminal <- List(T1, T2)
      minute <- List(minute1, minute2)
    } yield StaffMinute(terminal, minute.millisSinceEpoch, shifts, misc, moves))

    "When I ask for a desk recs CSV for multiple terminals I should get back a stream of CSV strings matching those minutes" >> {

      val crunchMinuteLookup = MockMinutesLookup.cmLookup(crunchMinutesContainer)
      val staffMinuteLookup = MockMinutesLookup.smLookup(staffMinutesContainer)

      val resultSource: Source[String, NotUsed] = StreamingDesksExport.deskRecsTerminalsToCSVStreamWithHeaders(
        start = minute1,
        end = minute2,
        terminals = List(T1, T2),
        exportQueuesInOrder = defaultAirportConfig.forecastExportQueueOrder,
        crunchMinuteLookup = crunchMinuteLookup,
        staffMinuteLookup = staffMinuteLookup,
        maybePit = None,
        periodMinutes = 15,
      )

      val result = takeCSVLines(resultStreamToCSV(resultSource), 6)

      val expected =
        s"""|Date,Terminal,,EEA,EEA,EEA,EEA,EEA,Non-EEA,Non-EEA,Non-EEA,Non-EEA,Non-EEA,e-Gates,e-Gates,e-Gates,e-Gates,e-Gates,Misc,Moves,PCP Staff,PCP Staff
            |,,Start,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Staff req,Act. wait time,Act. desks,Staff req,Staff movements,Avail,Req
            |2020-11-19,T1,00:00,$pax,$waitTime,$eeaDeskRec,$actWait,$actDesk,$pax,$waitTime,$nonEEADeskRec,$actWait,$actDesk,$pax,$waitTime,$eGateRec,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec
            |2020-11-19,T1,00:15,$pax,$waitTime,$eeaDeskRec,$actWait,$actDesk,$pax,$waitTime,$nonEEADeskRec,$actWait,$actDesk,$pax,$waitTime,$eGateRec,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec
            |2020-11-19,T2,00:00,$pax,$waitTime,$eeaDeskRec,$actWait,$actDesk,$pax,$waitTime,$nonEEADeskRec,$actWait,$actDesk,$pax,$waitTime,$eGateRec,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec
            |2020-11-19,T2,00:15,$pax,$waitTime,$eeaDeskRec,$actWait,$actDesk,$pax,$waitTime,$nonEEADeskRec,$actWait,$actDesk,$pax,$waitTime,$eGateRec,$actWait,$actDesk,$misc,$moves,$shifts,$totalRec"""
          .stripMargin

      result === expected
    }
  }
}
