package controllers

import com.typesafe.config.ConfigFactory
import controllers.Deskstats._
import drt.shared.{DeskStat, Queues}
import org.specs2.mutable.Specification
import services.SDate

import scala.collection.JavaConversions._
import scala.io.Source


object TestActorSystemConfig {
  def apply() = ConfigFactory.parseMap(Map("PORT_CODE" -> "LHR"))
}

class DeskstatsSpec extends Specification {

  "Deskstats " >> {
    "Can parse headings from blackjack CSV content " >> {
      val deskstatsContent =
        """"device","Date","Time","EEA desks open","Queue time EEA","Non EEA desks open","Queue time Non EEA","Fast Track desks open","Queue time Fast Track","Int/Dom desks open","Queue time Int/Dom","Comments"
          |"T2","29/06/2017","21:30 - 21:45","2","00:09","16","","2","00:00","0","00:00",""
        """.stripMargin

      val headings = csvHeadings(deskstatsContent)

      val expected = List(
        "device", "Date", "Time",
        "EEA desks open", "Queue time EEA",
        "Non EEA desks open", "Queue time Non EEA",
        "Fast Track desks open", "Queue time Fast Track",
        "Int/Dom desks open", "Queue time Int/Dom",
        "Comments"
      )

      headings === expected
    }

    "Can parse data from blackjack CSV content " >> {
      "When the date falls inside of BST " +
        "Then we should see millis after having first converted the date to UTC" >> {
        val deskstatsContent =
          """"device","Date","Time","EEA desks open","Queue time EEA","Non EEA desks open","Queue time Non EEA","Fast Track desks open","Queue time Fast Track","Int/Dom desks open","Queue time Int/Dom","Comments"
            |"T2","29/06/2017","21:30 - 21:45","2","00:09","16","","2","00:00","0","00:00",""
          """.stripMargin

        val data = csvData(deskstatsContent)

        val expected = Map(
          "T2" -> Map(
            Queues.EeaDesk -> Map(1498768200000L -> DeskStat(Some(2), Some(9))),
            Queues.NonEeaDesk -> Map(1498768200000L -> DeskStat(Some(16), None)),
            Queues.FastTrack -> Map(1498768200000L -> DeskStat(Some(2), Some(0)))))

        data === expected
      }

      "When the date falls outside BST " +
        "Then we should see millis directly converted from that date" >> {
        val deskstatsContent =
          """"device","Date","Time","EEA desks open","Queue time EEA","Non EEA desks open","Queue time Non EEA","Fast Track desks open","Queue time Fast Track","Int/Dom desks open","Queue time Int/Dom","Comments"
            |"T2","01/01/2017","21:30 - 21:45","2","00:09","16","","2","00:00","0","00:00",""
          """.stripMargin

        val data = csvData(deskstatsContent)

        val expected = Map(
          "T2" -> Map(
            Queues.EeaDesk -> Map(1483306200000L -> DeskStat(Some(2), Some(9))),
            Queues.NonEeaDesk -> Map(1483306200000L -> DeskStat(Some(16), None)),
            Queues.FastTrack -> Map(1483306200000L -> DeskStat(Some(2), Some(0)))))

        data === expected
      }
    }

    "We can get only the lines with a date after a threshold date" >> {
      val since = SDate("2017-01-01T12:00:00").millisSinceEpoch

      val allCsvLines =
        Source.fromString(""""device","Date","Time","EEA desks open","Queue time EEA","Non EEA desks open","Queue time Non EEA","Fast Track desks open","Queue time Fast Track","Int/Dom desks open","Queue time Int/Dom","Comments"
          |"T2","01/01/2017","21:30 - 21:45","2","00:09","16","","2","00:00","0","00:00",""
          |"T2","01/01/2017","09:30 - 09:45","2","00:09","16","","2","00:00","0","00:00",""
        """.stripMargin)

      val result = Deskstats.csvLinesUntil(allCsvLines, since)

      result === """"device","Date","Time","EEA desks open","Queue time EEA","Non EEA desks open","Queue time Non EEA","Fast Track desks open","Queue time Fast Track","Int/Dom desks open","Queue time Int/Dom","Comments"
                   |"T2","01/01/2017","21:30 - 21:45","2","00:09","16","","2","00:00","0","00:00",""""".stripMargin
    }
  }


}
