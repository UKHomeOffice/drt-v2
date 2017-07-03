package controllers

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import drt.shared.Queues
import org.specs2.mutable.SpecificationLike
import services.SDate

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}

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
          Queues.EeaDesk -> Map(1498771800000L -> Some(2)),
          Queues.NonEeaDesk -> Map(1498771800000L -> Some(16)),
          Queues.FastTrack -> Map(1498771800000L -> Some(2))
        )
      )

      data === expected
    }
  }

  private def csvHeadings(deskstatsContent: String): Seq[String] = {
    parseCsvLine(deskstatsContent.split("\n").head)
  }

  private def desksForQueueByMillis(queueName: String, dateIndex: Int, timeIndex: Int, deskIndex: Int, rows: Seq[Seq[String]]): Map[Long, Option[Int]] = {
    rows.filter(_.length == 11).map {
      case columnData: Seq[String] =>
        val desks = Try {
          columnData(deskIndex).toInt
        } match {
          case Success(d) => Option(d)
          case Failure(f) => None
        }
        val timeString = columnData(timeIndex).take(5)
        val dateString = {
          val Array(day, month, year) = columnData(dateIndex).split("/")
          s"$year-$month-$day"
        }
        val millis = SDate(s"${dateString}T$timeString").millisSinceEpoch
        (millis -> desks)
    }.toMap
  }

  private def csvData(deskstatsContent: String) = {
    val headings = csvHeadings(deskstatsContent)
    val columnIndices = Map(
      "terminal" -> headings.indexOf("device"),
      "date" -> headings.indexOf("Date"),
      "time" -> headings.indexOf("Time")
    )
    val queueColumns = queueColumnIndexes(headings)
    val rows = deskstatsContent.split("\n").drop(1).toList
    val parsedRows = rows.map(parseCsvLine).filter(_.length == 11)
    val dataByTerminal = parsedRows.groupBy(_ (columnIndices("terminal")))
    val dataByTerminalAndQueue =
      dataByTerminal.map {
        case (terminal, rows) =>
          terminal -> queueColumns.map {
            case (queueName, desksAndWaitIndexes) =>
              queueName -> desksForQueueByMillis(queueName, columnIndices("date"), columnIndices("time"), desksAndWaitIndexes("desks"), rows)
          }
      }

    dataByTerminalAndQueue
  }

  private def queueColumnIndexes(headings: Seq[String]) = {
    Map(
      Queues.EeaDesk -> Map(
        "desks" -> headings.indexOf("EEA desks open"),
        "wait" -> headings.indexOf("Queue time EEA")
      ),
      Queues.NonEeaDesk -> Map(
        "desks" -> headings.indexOf("Non EEA desks open"),
        "wait" -> headings.indexOf("Queue time Non EEA")
      ),
      Queues.FastTrack -> Map(
        "desks" -> headings.indexOf("Fast Track desks open"),
        "wait" -> headings.indexOf("Queue time Fast Track")
      )
    )
  }

  private def parseCsvLine(line: String): Seq[String] = {
    line.drop(1).dropRight(1).split("\",\"").toList
  }
}
