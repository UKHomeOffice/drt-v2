package services.crunch

import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}
import drt.shared.{ArrivalHelper, Queues, TM, TQM}
import org.specs2.matcher.Scope
import org.specs2.mutable.Specification
import services.{CSVData, SDate}

import scala.collection.SortedMap

class CrunchMinutesToCSVDataTest extends Specification {

  trait Context extends Scope {
    val header =  """|Date,,Q1,Q1,Q1,Q1,Q1,Q2,Q2,Q2,Q2,Q2,Q3,Q3,Q3,Q3,Q3,Misc,Moves,PCP Staff,PCP Staff
         |,Start,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Desks req,Act. wait time,Act. desks,Staff req,Staff movements,Avail,Req"""
  }

  "Given a set of crunch minutes for a terminal, we should receive a CSV for that terminals data" in new Context {
    val startDateTime = SDate("2017-11-10T00:00:00Z")
    val cms = SortedMap[TQM, CrunchMinute]() ++ List(
      CrunchMinute("T1", "Q1", startDateTime.millisSinceEpoch, 1.1, 1, deskRec = 1, 1),
      CrunchMinute("T1", "Q3", startDateTime.millisSinceEpoch, 1.3, 1, deskRec = 1, 1),
      CrunchMinute("T1", "Q2", startDateTime.millisSinceEpoch, 1.2, 1, deskRec = 1, 1)
    ).map(cm => (cm.key, cm))

    val staffMins = SortedMap[TM, StaffMinute]() ++ List(
      StaffMinute("T1", startDateTime.millisSinceEpoch, 5, fixedPoints = 1, movements = -1)
    ).map(sm => (sm.key, sm))

    val result = CSVData.terminalMinutesToCsvData(cms, staffMins, Seq("Q1", "Q2", "Q3"), startDateTime, startDateTime.addMinutes(14), 15)

    val expected = s"2017-11-10,00:00,1,1,1,,,1,1,1,,,1,1,1,,,1,-1,4,4"

    result === expected
  }

  "Given a set of crunch minutes for a terminal, we should get the result back in 15 minute increments" in new Context {
    val startDateTime = SDate("2017-11-10T00:00:00Z")

    val t15mins = startDateTime.addMinutes(15).millisSinceEpoch
    val t14mins = startDateTime.addMinutes(14).millisSinceEpoch
    val t13mins = startDateTime.addMinutes(13).millisSinceEpoch

    val cms = SortedMap[TQM, CrunchMinute]() ++ (0 until 16).flatMap((min: Int) => {
      List(
        CrunchMinute("T1", "Q1", startDateTime.addMinutes(min).millisSinceEpoch, 1.0, 1, deskRec = 1, 1),
        CrunchMinute("T1", "Q2", startDateTime.addMinutes(min).millisSinceEpoch, 1.0, 1, deskRec = 1, 1),
        CrunchMinute("T1", "Q3", startDateTime.addMinutes(min).millisSinceEpoch, 1.0, 1, deskRec = 1, 1)
      )
    }).map(cm => (cm.key, cm))

    val staffMins = SortedMap[TM, StaffMinute]() ++ (0 until 16).map((min: Int) => {
      StaffMinute("T1", startDateTime.addMinutes(min).millisSinceEpoch, 5, fixedPoints = 1, movements = -1)
    }).map(sm => (sm.key, sm))

    val result = CSVData.terminalMinutesToCsvData(cms, staffMins, Seq("Q1", "Q2", "Q3"), startDateTime, startDateTime.addMinutes(29), 15)

    val expected =
      s"""2017-11-10,00:00,15,1,1,,,15,1,1,,,15,1,1,,,1,-1,4,4
         |2017-11-10,00:15,1,1,1,,,1,1,1,,,1,1,1,,,1,-1,4,4""".stripMargin

    result === expected
  }

  "When exporting data, column names and order must exactly match V1" in {
    val startDateTime = SDate("2017-11-10T00:00:00Z")
    val cms = SortedMap[TQM, CrunchMinute]() ++ List(
      CrunchMinute("T1", Queues.EeaDesk, startDateTime.millisSinceEpoch, 1.0, 2.0, deskRec = 1, 100, Option(2), Option(100), Option(2), Option(100)),
      CrunchMinute("T1", Queues.EGate, startDateTime.millisSinceEpoch, 1.0, 2.0, deskRec = 1, 100, Option(2), Option(100), Option(2), Option(100)),
      CrunchMinute("T1", Queues.NonEeaDesk, startDateTime.millisSinceEpoch, 1.0, 2.0, deskRec = 1, 100, Option(2), Option(100), Option(2), Option(100))
    ).map(cm => (cm.key, cm))

    val staffMins = SortedMap[TM, StaffMinute]() ++ List(
      StaffMinute("T1", startDateTime.millisSinceEpoch, 5, fixedPoints = 1, movements = -1)
    ).map(sm => (sm.key, sm))

    val expected = "2017-11-10,00:00,1,100,1,100,2,1,100,1,100,2,1,100,1,100,2,1,-1,4,4"

    val result = CSVData.terminalMinutesToCsvData(cms, staffMins, Seq(Queues.EeaDesk, Queues.EGate, Queues.NonEeaDesk), startDateTime, startDateTime.addMinutes(14), 15)

    result === expected
  }

  "When exporting data without headings, then we should not get headings back" >> {
    val startDateTime = SDate("2017-11-10T00:00:00Z")
    val cms = SortedMap[TQM, CrunchMinute]() ++ List(
      CrunchMinute("T1", Queues.EeaDesk, startDateTime.millisSinceEpoch, 1.0, 2.0, deskRec = 1, 100, Option(2), Option(100), Option(2), Option(100)),
      CrunchMinute("T1", Queues.EGate, startDateTime.millisSinceEpoch, 1.0, 2.0, deskRec = 1, 100, Option(2), Option(100), Option(2), Option(100)),
      CrunchMinute("T1", Queues.NonEeaDesk, startDateTime.millisSinceEpoch, 1.0, 2.0, deskRec = 1, 100, Option(2), Option(100), Option(2), Option(100))
    ).map(cm => (cm.key, cm))

    val staffMins = SortedMap[TM, StaffMinute]() ++ List(
      StaffMinute("T1", startDateTime.millisSinceEpoch, 5, fixedPoints = 1, movements = -1)
    ).map(sm => (sm.key, sm))

    val expected = "2017-11-10,00:00,1,100,1,100,2,1,100,1,100,2,1,100,1,100,2,1,-1,4,4"

    val result = CSVData.terminalMinutesToCsvData(cms, staffMins, Seq(Queues.EeaDesk, Queues.EGate, Queues.NonEeaDesk), startDateTime, startDateTime.addMinutes(14), 15)

    result === expected
  }
}
