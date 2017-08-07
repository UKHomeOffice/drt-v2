package controllers

import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.TimeZone
import javax.net.ssl._

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem}
import drt.shared._
import org.joda.time.DateTimeZone
import org.slf4j.LoggerFactory
import services.SDate

import scala.concurrent.ExecutionContext
import scala.io.{BufferedSource, Source}
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

case class GetActualDeskStats()

class DeskstatsActor extends Actor with ActorLogging {
  var actualDeskStats = Map[String, Map[String, Map[Long, DeskStat]]]()

  override def receive: Receive = {
    case ActualDeskStats(deskStats) =>
      log.info(s"Received ActualDeskStats")
      actualDeskStats = deskStats
    case GetActualDeskStats() =>
      log.info(s"Sending ActualDeskStats to sender")
      sender ! ActualDeskStats(actualDeskStats)
  }
}

object Deskstats {
  val log = LoggerFactory.getLogger(getClass)

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
      context.getSocketFactory
    }
  }

  def startBlackjack(csvUrl: String, actualDesksActor: ActorRef, interval: FiniteDuration, startFrom: SDateLike)(implicit actorSystem: ActorSystem): Any = {
    val initialDelay1Second = 1 * 1000

    actorSystem.scheduler.schedule(
      initialDelay1Second milliseconds,
      interval) {
      val actDesks = Deskstats.blackjackDeskstats(csvUrl, startFrom)
      actualDesksActor ! ActualDeskStats(actDesks)
    }
  }

  def blackjackDeskstats(blackjackUrl: String, parseSince: SDateLike): Map[String, Map[String, Map[Long, DeskStat]]] = {
    val sc = SSLContext.getInstance("SSL")
    sc.init(null, Array(new NaiveTrustManager), new java.security.SecureRandom())
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory())
    val backupSslSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory

    log.info(s"DeskStats: requesting blackjack CSV from $blackjackUrl")
    val bufferedCsvContent: BufferedSource = Source.fromURL(blackjackUrl)
    log.info("DeskStats: received blackjack CSV")

    HttpsURLConnection.setDefaultSSLSocketFactory(backupSslSocketFactory)

    log.info(s"Asking for blackjack entries since $parseSince")
    val relevantData = csvLinesUntil(bufferedCsvContent, parseSince.millisSinceEpoch)
    csvData(relevantData)
  }

  def csvLinesUntil(csvContent: Source, until: Long): String = {
    csvContent.getLines().takeWhile(line => {
      val cells: Seq[String] = parseCsvLine(line)
      cells(0) match {
        case "device" => true
        case _ =>
          val statsDate: SDateLike = parseSDate(cells)
          statsDate.millisSinceEpoch > until
      }
    }).mkString("\n")
  }

  private def parseSDate(cells: Seq[String]) = {
    val (date, time) = (cells(1), cells(2).take(5))
    val Array(day, month, year) = date.split("/")
    val statsDate = SDate(s"$year-$month-${day}T$time:00", DateTimeZone.forTimeZone(TimeZone.getTimeZone("Europe/London")))
    statsDate
  }

  def csvHeadings(deskstatsContent: String): Seq[String] = {
    val firstLine = deskstatsContent.split("\n").head
    parseCsvLine(firstLine)
  }

  def desksForQueueByMillis(queueName: String, dateIndex: Int, timeIndex: Int, deskIndex: Int, waitTimeIndex: Int, rows: Seq[Seq[String]]): Map[Long, DeskStat] = {
    rows.map {
      case columnData: Seq[String] =>
        val desksOption = Try {
          columnData(deskIndex).toInt
        } match {
          case Success(d) => Option(d)
          case Failure(f) => None
        }
        val waitTimeOption = Try {
          log.debug(s"deskStats waitTime: ${columnData(waitTimeIndex)}, from columnData: ${columnData}")
          val Array(hours, minutes) = columnData(waitTimeIndex).split(":").map(_.toInt)
          (hours * 60) + minutes
        } match {
          case Success(d) => Option(d)
          case Failure(f) => None
        }
        parseSDate(columnData).millisSinceEpoch -> DeskStat(desksOption, waitTimeOption)
    }.toMap
  }


  def csvData(deskstatsContent: String): Map[String, Map[String, Map[Long, DeskStat]]] = {
    val headings = csvHeadings(deskstatsContent)
    log.debug(s"DeskStats: headings: $headings")
    val columnIndices = Map(
      "terminal" -> headings.indexOf("device"),
      "date" -> headings.indexOf("Date"),
      "time" -> headings.indexOf("Time")
    )
    val queueColumns = queueColumnIndexes(headings)

    val rows = deskstatsContent.split("\n").drop(1).toList
    log.debug(s"DeskStats: Got ${rows.length} relevant rows")
    val parsedRows = rows.map(parseCsvLine).filter(_.length == 11)
    val dataByTerminal = parsedRows.groupBy(_ (columnIndices("terminal")))
    val dataByTerminalAndQueue =
      dataByTerminal.map {
        case (terminal, rows) =>
          terminal -> queueColumns.map {
            case (queueName, desksAndWaitIndexes) =>
              queueName -> desksForQueueByMillis(queueName, columnIndices("date"), columnIndices("time"), desksAndWaitIndexes("desks"), desksAndWaitIndexes("wait"), rows)
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
