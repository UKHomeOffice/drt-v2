package services.crunch

import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}
import drt.shared.Queues
import org.specs2.mutable.Specification
import services.{CSVData, SDate}

class CrunchMinutesToCSVDataTest extends Specification {

  "Given a set of crunch minutes for a terminal, we should receive a CSV for that terminals data" >> {
    val startDateTime = SDate("2017-11-10T00:00:00Z")
    val cms = Set(
      CrunchMinute("T1", "Q1", startDateTime.millisSinceEpoch, 1.1, 1, 1, 1),
      CrunchMinute("T1", "Q3", startDateTime.millisSinceEpoch, 1.3, 1, 1, 1),
      CrunchMinute("T2", "Q1", startDateTime.millisSinceEpoch, 1.0, 1, 1, 1),
      CrunchMinute("T1", "Q2", startDateTime.millisSinceEpoch, 1.2, 1, 1, 1)
    )

    val staffMins = Set(
      StaffMinute("T1", startDateTime.millisSinceEpoch, 5, 1, 1)
    )

    val result = CSVData.terminalCrunchMinutesToCsvData(cms, staffMins, "T1", List("Q1", "Q2", "Q3"))

    val expected =
      """|,Q1,Q1,Q1,Q1,Q1,Q2,Q2,Q2,Q2,Q2,Q3,Q3,Q3,Q3,Q3,Misc,PCP Staff,PCP Staff
         |Start,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Desks req,Act. wait time,Act. desks,Staff req,Avail,Req
         |00:00,1,1,1,,,1,1,1,,,1,1,1,,,1,4,3""".stripMargin

    result === expected
  }

  "Given a set of crunch minutes for a terminal, we should get the result back in 15 minute increments" >> {
    val startDateTime = SDate("2017-11-10T00:00:00Z")

    val t15mins = startDateTime.addMinutes(15).millisSinceEpoch
    val t14mins = startDateTime.addMinutes(14).millisSinceEpoch
    val t13mins = startDateTime.addMinutes(13).millisSinceEpoch

    val cms = (0 until 16).toSet.flatMap((min: Int) => {
      Set(
        CrunchMinute("T1", "Q1", startDateTime.addMinutes(min).millisSinceEpoch, 1.0, 1, 1, 1),
        CrunchMinute("T1", "Q2", startDateTime.addMinutes(min).millisSinceEpoch, 1.0, 1, 1, 1),
        CrunchMinute("T1", "Q3", startDateTime.addMinutes(min).millisSinceEpoch, 1.0, 1, 1, 1)
      )
    })

    val staffMins = Set(
      StaffMinute("T1", startDateTime.millisSinceEpoch, 5, 1, 1)
    )

    val result = CSVData.terminalCrunchMinutesToCsvData(cms, staffMins, "T1", List("Q1", "Q2", "Q3"))

    val expected =
      """|,Q1,Q1,Q1,Q1,Q1,Q2,Q2,Q2,Q2,Q2,Q3,Q3,Q3,Q3,Q3,Misc,PCP Staff,PCP Staff
         |Start,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Desks req,Act. wait time,Act. desks,Staff req,Avail,Req
         |00:00,15,1,1,,,15,1,1,,,15,1,1,,,1,4,3
         |00:15,1,1,1,,,1,1,1,,,1,1,1,,,0,0,3""".stripMargin

    result === expected
  }

  "When exporting data, column names and order must exactly match V1" >> {
    val startDateTime = SDate("2017-11-10T00:00:00Z")
    val cms = Set(
      CrunchMinute("T1", Queues.EeaDesk, startDateTime.millisSinceEpoch, 1.0, 2.0, 1, 100, Option(2), Option(100), Option(2), Option(100)),
      CrunchMinute("T1", Queues.EGate, startDateTime.millisSinceEpoch, 1.0, 2.0, 1, 100, Option(2), Option(100), Option(2), Option(100)),
      CrunchMinute("T1", Queues.NonEeaDesk, startDateTime.millisSinceEpoch, 1.0, 2.0, 1, 100, Option(2), Option(100), Option(2), Option(100))
    )

    val staffMins = Set(
      StaffMinute("T1", startDateTime.millisSinceEpoch, 5, 1, 1)
    )

    val expected = """|,EEA,EEA,EEA,EEA,EEA,NON-EEA,NON-EEA,NON-EEA,NON-EEA,NON-EEA,E-GATES,E-GATES,E-GATES,E-GATES,E-GATES,Misc,PCP Staff,PCP Staff
                      |Start,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Desks req,Act. wait time,Act. desks,Pax,Wait,Staff req,Act. wait time,Act. desks,Staff req,Avail,Req
                      |00:00,1,100,1,100,2,1,100,1,100,2,1,100,1,100,2,1,4,3""".stripMargin

    val result = CSVData.terminalCrunchMinutesToCsvData(cms, staffMins, "T1", Queues.exportQueueOrder)

    result === expected
  }
}
