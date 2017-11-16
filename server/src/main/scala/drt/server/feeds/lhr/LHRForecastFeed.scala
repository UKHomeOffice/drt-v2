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
import scala.util.{Success, Try}

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
        case Success(s) =>
        case failure =>
          log.error(s"LHR Forecast: Failed to parse CSV Row: $failure")
      }

  def contentFromFileName(path: String): Seq[Seq[Arrival]] = unzipStream(
    new ZipInputStream(
      new FileInputStream(path)
    )
  )

  def unzipStream(zipInputStream: ZipInputStream): Seq[Seq[Arrival]] = {
    try {
      unzipAllFilesInStream(zipInputStream).toList
    } finally {
      zipInputStream.close()
    }
  }

  def unzipAllFilesInStream(unzippedStream: ZipInputStream): Seq[Seq[Arrival]] = unzipAllFilesInStream(
    unzippedStream,
    Option(unzippedStream.getNextEntry)
  )

  val terminalCSVFile = "[^/]+/(T.).csv".r

  def unzipAllFilesInStream(unzippedStream: ZipInputStream, zipEntryOption: Option[ZipEntry]): Stream[Seq[Arrival]] =
    zipEntryOption match {
      case None => Stream.empty
      case Some(zipEntry) =>
        zipEntry.getName match {
          case terminalCSVFile(terminal) =>
            val csv: String = getZipEntry(unzippedStream)
            val terminalArrivals = parseCSV(csv, terminal)
            val maybeEntry1: Option[ZipEntry] = Option(unzippedStream.getNextEntry)
            terminalArrivals #:: unzipAllFilesInStream(unzippedStream, maybeEntry1)
          case nonCSVFile =>
            unzipAllFilesInStream(unzippedStream, Option(unzippedStream.getNextEntry))
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
