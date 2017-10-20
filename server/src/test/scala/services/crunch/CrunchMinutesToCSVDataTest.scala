package services.crunch

import drt.shared.CrunchApi.CrunchMinute
import org.specs2.mutable.Specification
import services.CSVData

class CrunchMinutesToCSVDataTest extends Specification{

  "Given a set of crunch minutes for a terminal, we should receive a CSV for that terminals data" >> {
    val t15mins = 60000 * 15
    val cms = Set(
      CrunchMinute("T1", "Q1", 0, 1.1, 1, 1, 1),
      CrunchMinute("T1", "Q1", t15mins, 2.1, 1, 1, 1),
      CrunchMinute("T1", "Q3", 0, 1.3, 1, 1, 1),
      CrunchMinute("T1", "Q2", t15mins, 2.2, 1, 1, 1),
      CrunchMinute("T2", "Q1", 0, 1.0, 1, 1, 1),
      CrunchMinute("T1", "Q2", 0, 1.2, 1, 1, 1),
      CrunchMinute("T1", "Q3", t15mins, 2.3, 1, 1, 1)
    )

    val result = CSVData.terminalCrunchMinutesToCsvData(cms, "T1", List("Q1", "Q2", "Q3"))

    val expected =
      """ |,Q1,Q1,Q1,Q2,Q2,Q2,Q3,Q3,Q3
        |Start,Pax,Wait,Desks req,Pax,Wait,Desks req,Pax,Wait,Desks req
        |00:00,1,1,1,1,1,1,1,1,1
        |00:15,2,1,1,2,1,1,2,1,1""".stripMargin

    result === expected
  }

  "Given a set of crunch minutes for a terminal, we should get the result back in 15 minute increments" >> {
    val t15mins = 60000 * 15
    val t14mins = 60000 * 14
    val t13mins = 60000 * 13
    val cms = Set(
      CrunchMinute("T1", "Q1", 0, 1.1, 1, 1, 1),
      CrunchMinute("T1", "Q1", t15mins, 2.1, 1, 1, 1),
      CrunchMinute("T1", "Q3", 0, 1.3, 1, 1, 1),
      CrunchMinute("T1", "Q2", t15mins, 2.2, 1, 1, 1),
      CrunchMinute("T2", "Q1", 0, 1.0, 1, 1, 1),
      CrunchMinute("T1", "Q2", 0, 1.2, 1, 1, 1),
      CrunchMinute("T1", "Q3", t15mins, 2.3, 1, 1, 1),
      CrunchMinute("T1", "Q1", t14mins, 1.1, 1, 1, 1),
      CrunchMinute("T1", "Q1", t13mins, 2.1, 1, 1, 1),
      CrunchMinute("T1", "Q3", t14mins, 1.3, 1, 1, 1),
      CrunchMinute("T1", "Q2", t13mins, 2.2, 1, 1, 1),
      CrunchMinute("T2", "Q1", t14mins, 1.0, 1, 1, 1),
      CrunchMinute("T1", "Q2", t14mins, 1.2, 1, 1, 1),
      CrunchMinute("T1", "Q3", t13mins, 2.3, 1, 1, 1)
    )

    val result = CSVData.terminalCrunchMinutesToCsvData(cms, "T1", List("Q1", "Q2", "Q3"))

    val expected =
      """ |,Q1,Q1,Q1,Q2,Q2,Q2,Q3,Q3,Q3
        |Start,Pax,Wait,Desks req,Pax,Wait,Desks req,Pax,Wait,Desks req
        |00:00,1,1,1,1,1,1,1,1,1
        |00:15,2,1,1,2,1,1,2,1,1""".stripMargin

    result === expected
  }
}
