package controllers

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl._

import akka.actor.ActorSystem
import akka.testkit.TestKit
//import com.sun.net.ssl.{HttpsURLConnection, SSLContext, X509TrustManager}
import com.typesafe.config.ConfigFactory
import drt.shared.{Queues, SDateLike}
import org.specs2.mutable.SpecificationLike
import services.SDate

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}
import scala.io.{BufferedSource, Source}

//class DeskstatsActor extends Actor with ActorLogging {
//  def receive: PartialFunction[Any, Unit] = LoggingReceive {
//    case something => Unit
//  }
//}


object TestActorSystemConfig {
  def apply() = ConfigFactory.parseMap(Map("PORT_CODE" -> "LHR"))
}

class DeskstatsSpec extends TestKit(ActorSystem("testActorSystem", TestActorSystemConfig())) with SpecificationLike {

  //  implicit val materializer = ActorMaterializer()
  //  implicit val timeout = Timeout(1 second)
  //
  //  val deskstatsActorRef = system.actorOf(Props[DeskstatsActor], name = "desk-stats-reporter")

  class NaiveTrustManager extends X509TrustManager {
    override def checkClientTrusted(cert: Array[X509Certificate], authType: String) {}
    override def checkServerTrusted(cert: Array[X509Certificate], authType: String) {}
    override def getAcceptedIssuers = null
  }

  object NaiveTrustManager {
    def getSocketFactory: SSLSocketFactory = {
      val tm = Array[TrustManager](new NaiveTrustManager())
      val context = SSLContext.getInstance("SSL")
      context.init(new Array[KeyManager](0), tm, new SecureRandom())
      return context.getSocketFactory()
    }
  }


  "Fetching the CSV" >> {
    val data = blackjackDeskstats("https://hal.abm.com/admin/csv/index", SDate("2017-07-03").millisSinceEpoch)

    println(s"data: $data")
    1 == 1
  }

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

  object Deskstats {
    def blackjackDeskstats(blackjackUrl: String, parseSinceMillis: Long): Map[String, Map[String, Map[Long, Option[Int]]]] = {
      val sc = SSLContext.getInstance("SSL")
      sc.init(null, Array(new NaiveTrustManager), new java.security.SecureRandom())
      HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory())
      val backupSslSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory

      val bufferedCsvContent: BufferedSource = Source.fromURL(blackjackUrl)

      HttpsURLConnection.setDefaultSSLSocketFactory(backupSslSocketFactory)

      val relevantData = csvLinesUntil(bufferedCsvContent, parseSinceMillis)
      csvData(relevantData)
    }

    def csvLinesUntil(csvContent: BufferedSource, until: Long): String = {
      csvContent.getLines().takeWhile(line => {
        val cells: Seq[String] = parseCsvLine(line)
        cells(0) match {
          case "device" => true
          case _ =>
            val (date, time) = (cells(1), cells(2).take(5))
            val Array(day, month, year) = date.split("/")
            val statsDate = SDate(s"$year-$month-${day}T$time:00Z")
            statsDate.millisSinceEpoch > until
        }
      }).mkString("\n")
    }

    def csvHeadings(deskstatsContent: String): Seq[String] = {
      parseCsvLine(deskstatsContent.split("\n").head)
    }

    def desksForQueueByMillis(queueName: String, dateIndex: Int, timeIndex: Int, deskIndex: Int, rows: Seq[Seq[String]]): Map[Long, Option[Int]] = {
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

    def csvData(deskstatsContent: String): Map[String, Map[String, Map[Long, Option[Int]]]] = {
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

    def queueColumnIndexes(headings: Seq[String]) = {
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

    def parseCsvLine(line: String): Seq[String] = {
      line.drop(1).dropRight(1).split("\",\"").toList
    }
  }

}
