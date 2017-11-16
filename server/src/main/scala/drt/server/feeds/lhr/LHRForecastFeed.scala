package drt.server.feeds.lhr

import java.io.FileInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.{ZipEntry, ZipInputStream}

import org.slf4j.LoggerFactory
import drt.shared.Arrival
import drt.shared.FlightsApi.{Flights, TerminalName}
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import services.SDate

import scala.collection.immutable.Seq
import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}

case class LHRForecastFeed(path: String) {

  def arrivals: List[Arrival] = LHRForecastFeed.contentFromFileName(path).flatten.toList

}

object LHRForecastFeed {

  def log = LoggerFactory.getLogger(classOf[LHRForecastFeed])

  def parseCSV(csvContent: String, terminalName: TerminalName) = {

    val lineEnding = if (csvContent.contains("\r\n")) "\r\n" else "\n"
    val flightEntries = csvContent
      .split(lineEnding)
      .drop(3)

    log.info(s"LHR Forecast: About to parse ${flightEntries.size} rows")

    def lhrFieldsToArrivalForTerminal: (List[String]) => Try[Arrival] = lhrFieldsToArrival(terminalName)

    val parsedArrivals = flightEntries
      .map(_.split(",").toList)
      .filter(_.length == 8)
      .filter(_ (4) == "INTERNATIONAL")
      .map(lhrFieldsToArrival(terminalName))

    logErrors(parsedArrivals)

    val arrivals = parsedArrivals.collect { case Success(a) => a }.toList
    log.info(s"LHR Forecast: Successfully parsed ${arrivals.size} rows")
    arrivals
  }

  def logErrors(parsedArrivals: Array[Try[Arrival]]) =
    parsedArrivals
      .foreach {
        case Failure(f) =>
          log.error(s"LHR Forecast: Failed to parse CSV Row: $f")
        case _ =>
      }

  def contentFromFileName(path: String): Seq[Seq[Arrival]] = unzipStream(
    new ZipInputStream(
      new FileInputStream(path)
    )
  )

  def unzipStream(zipInputStream: ZipInputStream): Seq[Seq[Arrival]] = {
    val arrivals = Try{
      log.info(s"LHR Forecast: About to unzip.")
      unzipAllFilesInStream(zipInputStream).toList
    } match {
      case Success(s) =>
        log.info(s"LHR Forecast: got ${s.size} terminals")
        s
      case f =>
        log.error(s"Failed to unzip file: $f")
        Seq()
    }
    zipInputStream.close()
    arrivals
  }

  def unzipAllFilesInStream(unzippedStream: ZipInputStream): Seq[Seq[Arrival]] = unzipAllFilesInStream(
    unzippedStream,
    Option(unzippedStream.getNextEntry)
  )

  val terminalCSVFile = "([^/]+/)?(T.).csv".r

  def unzipAllFilesInStream(unzippedStream: ZipInputStream, zipEntryOption: Option[ZipEntry]): Stream[Seq[Arrival]] = {
    log.info(s"LHR Forecast: Recursively unzipping files")

    zipEntryOption match {
      case None => Stream.empty
      case Some(zipEntry) =>
        zipEntry.getName match {
          case terminalCSVFile(_, terminal) =>
            log.info(s"LHR Forecast: Processing terminal $terminal")
            val csv: String = getZipEntry(unzippedStream)
            val terminalArrivals = parseCSV(csv, terminal)
            val maybeEntry1: Option[ZipEntry] = Option(unzippedStream.getNextEntry)
            terminalArrivals #:: unzipAllFilesInStream(unzippedStream, maybeEntry1)
          case nonCSVFile =>
            log.info(s"LHR Forecast: Skipping $nonCSVFile found in Zip (doesn't match terminal regex)")

            unzipAllFilesInStream(unzippedStream, Option(unzippedStream.getNextEntry))
        }
    }
  }

  def getZipEntry(zis: ZipInputStream): String = {
    val buffer = new Array[Byte](4096)
    val stringBuffer = new ArrayBuffer[Byte]()
    var len: Int = zis.read(buffer)

    while (len > 0) {
      stringBuffer ++= buffer.take(len)
      len = zis.read(buffer)
    }
    new String(stringBuffer.toArray, UTF_8)
  }

  val pattern: DateTimeFormatter = DateTimeFormat.forPattern("dd/MM/YYYY HH:mm")

  def parseDateTime(dateString: String): DateTime = pattern.parseDateTime(dateString)

  def lhrFieldsToArrival(terminalName: TerminalName)(fields: List[String]): Try[Arrival] = {
    Try {
      Arrival(
        Operator = "",
        Status = "Port Forecast",
        EstDT = "",
        ActDT = "",
        EstChoxDT = "",
        ActChoxDT = "",
        Gate = "",
        Stand = "",
        MaxPax = 0,
        ActPax = fields(5).toInt,
        TranPax = fields(7).toInt,
        RunwayID = "",
        BaggageReclaimId = "",
        FlightID = 0,
        AirportID = "LHR",
        Terminal = terminalName,
        rawICAO = fields(2).replace(" ", ""),
        rawIATA = fields(2).replace(" ", ""),
        Origin = fields(3),
        SchDT = SDate(parseDateTime(fields(1))).toISOString(),
        Scheduled = SDate(parseDateTime(fields(1))).millisSinceEpoch,
        PcpTime = 0,
        None
      )
    }
  }
}
