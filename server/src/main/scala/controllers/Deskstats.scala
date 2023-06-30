package controllers

import akka.actor.{ActorSystem, Scheduler}
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.CrunchApi.{ActualDeskStats, DeskStat}
import org.joda.time.DateTimeZone
import org.slf4j.{Logger, LoggerFactory}
import services.OfferHandler
import services.graphstages.Crunch.europeLondonId
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.TimeZone
import javax.net.ssl._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.io.{BufferedSource, Source}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object Deskstats {
  val log: Logger = LoggerFactory.getLogger(getClass)

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

  def startBlackjack(csvUrl: String,
                     actualDesksSource: SourceQueueWithComplete[ActualDeskStats],
                     interval: FiniteDuration,
                     startFrom: () => SDateLike)(implicit actorSystem: ActorSystem): Any = {
    val initialDelay1Second = 1 * 1000

    implicit val scheduler: Scheduler = actorSystem.scheduler

    scheduler.schedule(
      initialDelay1Second milliseconds,
      interval) {
      val actDesks = Deskstats.blackjackDeskstats(csvUrl, startFrom())
      OfferHandler.offerWithRetries(actualDesksSource, ActualDeskStats(actDesks), 5)
    }
  }

  def blackjackDeskstats(blackjackBaseUrl: String, parseSince: SDateLike): Map[Terminal, Map[Queue, Map[Long, DeskStat]]] = {
    val blackjackFullUrl = blackjackBaseUrl + uriForDate(parseSince)

    val sc = SSLContext.getInstance("SSL")
    sc.init(null, Array(new NaiveTrustManager), new java.security.SecureRandom())
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory)
    val backupSslSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory

    log.info(s"DeskStats: requesting blackjack CSV from $blackjackFullUrl")
    val bufferedCsvContent: BufferedSource = Source.fromURL(blackjackFullUrl)
    log.info("DeskStats: received blackjack CSV")

    HttpsURLConnection.setDefaultSSLSocketFactory(backupSslSocketFactory)

    log.info(s"Asking for blackjack entries since $parseSince")
    val relevantData = csvLinesUntil(bufferedCsvContent, parseSince.millisSinceEpoch)
    csvData(relevantData)
  }

  def uriForDate(date: SDateLike): String = {
    val startDate = date.toISODateOnly
    val endDate = date.addDays(2).toISODateOnly
    s"?date_limit=&start_date=$startDate&end_date=$endDate"
  }

  def csvLinesUntil(csvContent: Source, until: Long): String = {
    csvContent.getLines().takeWhile(line => {
      val cells: Seq[String] = parseCsvLine(line)
      cells.head match {
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
    val statsDate = SDate(s"$year-$month-${day}T$time:00", DateTimeZone.forTimeZone(TimeZone.getTimeZone(europeLondonId)))
    statsDate
  }

  def csvHeadings(deskstatsContent: String): Seq[String] = {
    val firstLine = deskstatsContent.split("\n").head
    parseCsvLine(firstLine)
  }

  def desksForQueueByMillis(deskIndex: Int, waitTimeIndex: Int, rows: Seq[Seq[String]]): Map[Long, DeskStat] = {
    rows.map { columnData: Seq[String] =>
      val desksOption = Try {
        columnData(deskIndex).toInt
      } match {
        case Success(d) => Option(d)
        case Failure(_) =>
          log.info(s"couldn't parse desks at index $deskIndex from '$columnData'")
          None
      }
      val waitTimeOption = Try {
        log.debug(s"deskStats waitTime: ${columnData(waitTimeIndex)}, from columnData: $columnData")
        val Array(hours, minutes) = columnData(waitTimeIndex).split(":").map(_.toInt)
        (hours * 60) + minutes
      } match {
        case Success(d) => Option(d)
        case Failure(_) =>
          log.info(s"couldn't parse wait time at index $waitTimeIndex from '$columnData'")
          None
      }
      parseSDate(columnData).millisSinceEpoch -> DeskStat(desksOption, waitTimeOption)
    }.toMap
  }

  def csvData(deskstatsContent: String): Map[Terminal, Map[Queue, Map[Long, DeskStat]]] = {
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
    val parsedRows = rows.map(parseCsvLine).filter(_.length == 12)
    val dataByTerminal = parsedRows.groupBy(_ (columnIndices("terminal")))
    val dataByTerminalAndQueue =
      dataByTerminal.map {
        case (terminal, rs) =>
          Terminal(terminal) -> queueColumns.map {
            case (queueName, desksAndWaitIndexes) =>
              queueName -> desksForQueueByMillis(desksAndWaitIndexes("desks"), desksAndWaitIndexes("wait"), rs)
          }
      }

    dataByTerminalAndQueue
  }

  def queueColumnIndexes(headings: Seq[String]): Map[Queue, Map[String, Int]] = {
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
    line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)").toList.map(stripQuotes)
  }

  def stripQuotes(cell: String): String = {
    val length = cell.length
    if (length > 0 && cell(0) == '"' && cell(cell.length - 1) == '"') {
      cell.drop(1).dropRight(1)
    } else cell
  }
}
