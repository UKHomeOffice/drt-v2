package controllers

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.event.LoggingReceive
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.SpecificationLike

import scala.collection.JavaConversions._
import scala.collection.immutable
import scala.concurrent.duration._

//class DeskstatsActor extends Actor with ActorLogging {
//  def receive: PartialFunction[Any, Unit] = LoggingReceive {
//    case something => Unit
//  }
//}


object TestActorSystemConfig {
  def apply() = ConfigFactory.parseMap(Map("PORT_CODE" -> "LHR"))
}

class DeskstatsActorSpec extends TestKit(ActorSystem("testActorSystem", TestActorSystemConfig())) with SpecificationLike {
  //  implicit val materializer = ActorMaterializer()
  //  implicit val timeout = Timeout(1 second)
  //
  //  val deskstatsActorRef = system.actorOf(Props[DeskstatsActor], name = "desk-stats-reporter")


  "DeskstatsActor " >> {
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
      val deskstatsContent =
        """"device","Date","Time","EEA desks open","Queue time EEA","Non EEA desks open","Queue time Non EEA","Fast Track desks open","Queue time Fast Track","Int/Dom desks open","Queue time Int/Dom","Comments"
          |"T2","29/06/2017","21:30 - 21:45","2","00:09","16","","2","00:00","0","00:00",""
        """.stripMargin

      val data = csvData(deskstatsContent)

      val expected = Map(
        "T2" -> Map(
          "2017-06-29T21:45" -> Map(
            "EEA" -> Map("desks" -> Some(2), "wait" -> Some(9)),
            "Non EEA" -> Map("desks" -> Some(16), "wait" -> None),
            "Fast Track" -> Map("desks" -> Some(2), "wait" -> Some(0)),
            "Int/Dom" -> Map("desks" -> Some(0), "wait" -> Some(0))
          )
        )
      )

      data === expected
    }
  }

  private def csvHeadings(deskstatsContent: String): Seq[String] = {
    parseCsvLine(deskstatsContent.split("\n").head)
  }

  private def csvData(deskstatsContent: String) = {
    parseCsvLine(deskstatsContent.split("\n").head)
  }

  private def parseCsvLine(line: String): Seq[String] = {
    line.drop(1).dropRight(1).split("\",\"").toList
  }
}