package controllers

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import drt.shared.Queues
import org.specs2.mutable.SpecificationLike
import services.SDate
import controllers.Deskstats._

import scala.collection.JavaConversions._


object TestActorSystemConfig {
  def apply() = ConfigFactory.parseMap(Map("PORT_CODE" -> "LHR"))
}

class DeskstatsSpec extends TestKit(ActorSystem("testActorSystem", TestActorSystemConfig())) with SpecificationLike {

  "DeskstatsActor " >> {
    "Can parse headings from blackjack CSV content " >> {
      val deskstatsContent =
        """"device","Date","Time","EEA desks open","Queue time EEA","Non EEA desks open","Queue time Non EEA","Fast Track desks open","Queue time Fast Track","Int/Dom desks open","Queue time Int/Dom","Comments"
          |"T2","29/06/2017","21:30 - 21:45","2","00:09","16","","2","00:00","0","00:00",""
        """.stripMargin

      val

      headings = csvHeadings(deskstatsContent)

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
      val deskstatsContent =
        """"device","Date","Time","EEA desks open","Queue time EEA","Non EEA desks open","Queue time Non EEA","Fast Track desks open","Queue time Fast Track","Int/Dom desks open","Queue time Int/Dom","Comments"
          |"T2","29/06/2017","21:30 - 21:45","2","00:09","16","","2","00:00","0","00:00",""
        """.stripMargin

      val data = csvData(deskstatsContent)

      val expected = Map(
        "T2" -> Map(
          Queues.EeaDesk -> Map(1498771800000L -> Some(2)),
          Queues.NonEeaDesk -> Map(1498771800000L -> Some(16)),
          Queues.FastTrack -> Map(1498771800000L -> Some(2))
        )
      )

      data === expected
    }
  }


}
