package drt.server.feeds.acl

import drt.server.feeds.Implicits._
import drt.server.feeds.acl.AclFeed._
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import drt.shared.FlightsApi.Flights
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.xfer.InMemoryDestFile
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers, Predictions}
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.ports.{AclFeedSource, PortCode, Terminals}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.{ZipEntry, ZipInputStream}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.{Failure, Success, Try}

case class AclFeed(ftpServer: String, username: String, path: String, portCode: PortCode, terminalMapping: Terminal => Terminal) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def ssh: SSHClient = sshClient(ftpServer, username, path)

  def requestArrivals: ArrivalsFeedResponse = {
    val trySftpClient = for {
      sshClient <- Try(ssh)
      sftpClient: SFTPClient <- Try(sshClient.newSFTPClient)
    } yield {
      (sshClient, sftpClient)
    }
    trySftpClient.map { case (sshClient, sftpClient) =>
      val directoryName = "180_Days"
      val allFiles = sftpClient.ls(directoryName).asScala.map(_.getName)
      val arrivalsFeedResponse = maybeLatestFile(allFiles, portCode.iata, SDate.now())
        .map { latestFile =>
          val latestFilePath = s"$directoryName/$latestFile"
          log.info(s"Latest ACL file: $latestFilePath")
          val feedResponseTry = Try(
            Flights(arrivalsFromCsvContent(contentFromFileName(sftpClient, latestFilePath), terminalMapping))
          )

          feedResponseTry match {
            case Success(a) =>
              ArrivalsFeedSuccess(a)
            case Failure(t) =>
              log.error(s"Failed to get flights from ACL: $t")
              ArrivalsFeedFailure(t.getMessage)
          }
        }
        .getOrElse {
          val msg = "No ACL file found for yesterday or today"
          log.error(msg)
          ArrivalsFeedFailure(msg)
        }

      sftpClient.close()
      sshClient.disconnect()

      arrivalsFeedResponse
    } match {
      case Success(response) => response
      case Failure(t) =>
        log.error(s"Failed to get flights from ACL: $t")
        ArrivalsFeedFailure("Failed to connect to sftp server")
    }
  }
}

object AclFeed {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def sshClient(ftpServer: String, username: String, path: String): SSHClient = {
    val ssh = new SSHClient()
    ssh.loadKnownHosts()
    ssh.addHostKeyVerifier(new PromiscuousVerifier())
    ssh.connect(ftpServer)
    ssh.authPublickey(username, path)
    ssh.setTimeout(5000)
    ssh
  }

  def sftpClient(sshClient: SSHClient): SFTPClient = {
    sshClient.newSFTPClient
  }

  def nextAclCheck(now: SDateLike, updateHour: Int): SDateLike = {
    val todaysCheck = SDate(now.getFullYear, now.getMonth, now.getDate, updateHour, 0, Crunch.europeLondonTimeZone)
    if (todaysCheck > now) todaysCheck else todaysCheck.addDays(1)
  }

  def delayUntilNextAclCheck(now: SDateLike, updateHour: Int): FiniteDuration = {
    val nextCheck = nextAclCheck(now, updateHour)
    (nextCheck.millisSinceEpoch - now.millisSinceEpoch).millis
  }

  def maybeLatestFile(allFiles: Iterable[String], portCode: String, now: SDateLike): Option[String] =
    List(0, 1, 2, 3, 4)
      .map { offset =>
        val d = now.addDays(-1 * offset)
        val todayStr = f"${d.getFullYear}${d.getMonth}%02d${d.getDate}%02d"
        s"$portCode.*$todayStr\\.zip".r
      }
      .map(fileRegex => allFiles.find(fileName => fileRegex.findFirstMatchIn(fileName).isDefined))
      .find(_.isDefined)
      .flatten

  def arrivalsFromCsvContent(csvContent: String, terminalMapping: Terminal => Terminal): List[Arrival] = {
    val flightEntries = csvContent
      .split("\n")
      .drop(1)

    val arrivalEntries = flightEntries
      .map(_.split(",").toList)
      .filter(_.length == 30)
      .filter(_ (AclColIndex.ArrDep) == "A")
      .filter(f => f(AclColIndex.FlightNumber) match {
        case Arrival.flightCodeRegex(_, _, suffix) => !(suffix == "P" || suffix == "F")
        case _ => true
      })

    val arrivals = arrivalEntries
      .map(fields => aclFieldsToArrival(fields, terminalMapping))
      .collect { case Success(a) => a }
      .toList

    if (arrivals.nonEmpty) {
      val latestArrival = arrivals.maxBy(_.Scheduled)
      log.info(s"ACL: ${arrivals.length} arrivals. Latest scheduled arrival: ${SDate(latestArrival.Scheduled).toLocalDateTimeString} (${latestArrival.flightCodeString})")
    }
    arrivals
  }

  private def contentFromFileName(sftp: SFTPClient, latestFileName: String): String = {
    val outputStream: ByteArrayOutputStream = new ByteArrayOutputStream()

    val file: InMemoryDestFile = new InMemoryDestFile {
      def getOutputStream: ByteArrayOutputStream = outputStream
    }

    sftp.get(latestFileName, file)

    val zis: ZipInputStream = new ZipInputStream(new ByteArrayInputStream(outputStream.toByteArray))

    val csvContent: String = unzipStream(zis).toList.head

    dropFileNameFromContent(csvContent)
  }

  private def dropFileNameFromContent(content: String): String = content
    .split("\n")
    .drop(1)
    .mkString("\n")

  private def unzipStream(zipInputStream: ZipInputStream): Seq[String] = {
    try {
      unzipAllFilesInStream(zipInputStream).toList
    } finally {
      zipInputStream.close()
    }
  }

  private def unzipAllFilesInStream(unzippedStream: ZipInputStream): Stream[String] = {
    unzipAllFilesInStream(unzippedStream, Option(unzippedStream.getNextEntry))
  }

  private def unzipAllFilesInStream(unzippedStream: ZipInputStream, zipEntryOption: Option[ZipEntry]): Stream[String] = {
    zipEntryOption match {
      case None => Stream.empty
      case Some(_) =>
        val entry: String = getZipEntry(unzippedStream)
        val maybeEntry1: Option[ZipEntry] = Option(unzippedStream.getNextEntry)
        entry #::
          unzipAllFilesInStream(unzippedStream, maybeEntry1)
    }
  }

  private def getZipEntry(zis: ZipInputStream): String = {
    val buffer = new Array[Byte](4096)
    val stringBuffer = new ArrayBuffer[Byte]()
    var len: Int = zis.read(buffer)

    while (len > 0) {
      stringBuffer ++= buffer.take(len)
      len = zis.read(buffer)
    }

    new String(stringBuffer.toArray, UTF_8)
  }

  private def dateAndTimeToDateTimeIso(date: String, time: String): String = s"${date}T${formatTimeToIso(time)}"

  private def formatTimeToIso(time: String): String = f"${time.toInt}%04d".splitAt(2) match {
    case (hour, minute) => s"$hour:$minute:00Z"
  }

  private def aclFieldsToArrival(fields: List[String], aclToPortTerminal: Terminal => Terminal): Try[Arrival] = {
    Try {
      val operator: String = fields(AclColIndex.Operator)
      val maxPax = fields(AclColIndex.MaxPax).toInt
      val actPax = (fields(AclColIndex.MaxPax).toInt * fields(AclColIndex.LoadFactor).toDouble).round.toInt
      val aclTerminal = Terminals.Terminal(fields(AclColIndex.Terminal))
      val portTerminal = aclToPortTerminal(aclTerminal)

      Arrival(
        Operator = operator,
        Status = "ACL Forecast",
        Estimated = None,
        Predictions = Predictions(0L, Map()),
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = Option(maxPax),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = fields(AclColIndex.Airport),
        Terminal = portTerminal,
        rawICAO = fields(AclColIndex.FlightNumber),
        rawIATA = fields(AclColIndex.FlightNumber),
        Origin = fields(AclColIndex.Origin),
        Scheduled = SDate(dateAndTimeToDateTimeIso(fields(AclColIndex.Date), fields(AclColIndex.Time))).millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(AclFeedSource),
        PassengerSources = Map(AclFeedSource -> Passengers(Option(actPax),None))
      )
    }
  }

  private object AclColIndex {
    private val allFields: Map[String, Int] = List(
      "A/C", "ACReg", "Airport", "ArrDep", "CreDate",
      "Date", "DOOP", "EditDate", "Icao Aircraft Type", "Icao Last/Next Station",
      "Icao Orig/Dest Station", "LastNext", "LastNextCountry", "Ope", "OpeGroup",
      "OpeName", "OrigDest", "OrigDestCountry", "Res", "Season",
      "Seats", "ServNo", "ST", "ove.ind", "Term",
      "Time", "TurnOpe", "TurnServNo", "OpeFlightNo", "LoadFactor"
    ).zipWithIndex.toMap

    val MaxPax: Int = allFields("Seats")
    val LoadFactor: Int = allFields("LoadFactor")
    val FlightNumber: Int = allFields("OpeFlightNo")
    val Date: Int = allFields("Date")
    val Time: Int = allFields("Time")
    val Operator: Int = allFields("Ope")
    val Origin: Int = allFields("OrigDest")
    val Airport: Int = allFields("Airport")
    val Terminal: Int = allFields("Term")
    val ArrDep: Int = allFields("ArrDep")
  }

  def aclToPortMapping(portCode: PortCode): Terminal => Terminal = portCode match {
    case PortCode("LGW") => (tIn: Terminal) =>
      Map[Terminal, Terminal](
        T1 -> S,
        T2 -> N,
      ).getOrElse(tIn, tIn)
    case PortCode("EDI") => (tIn: Terminal) =>
      Map[Terminal, Terminal](T1 -> A2).getOrElse(tIn, tIn)
    case PortCode("LCY") => (tIn: Terminal) =>
      Map[Terminal, Terminal](
        ACLTER -> T1,
        MainApron -> T1,
      ).getOrElse(tIn, tIn)
    case PortCode("STN") =>
      (tIn: Terminal) => Map[Terminal, Terminal](CTA -> T1).getOrElse(tIn, tIn)
    case _ => (tIn: Terminal) => tIn
  }
}
