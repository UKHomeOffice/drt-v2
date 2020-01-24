package drt.server.feeds.acl

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.{ZipEntry, ZipInputStream}

import drt.server.feeds.Implicits._
import drt.server.feeds.acl.AclFeed._
import drt.shared
import drt.shared.FlightsApi.Flights
import drt.shared.Terminals._
import drt.shared.{Arrival, PortCode, Terminals}
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.{RemoteResourceInfo, SFTPClient}
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.xfer.InMemoryDestFile
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}

case class AclFeed(ftpServer: String, username: String, path: String, portCode: PortCode, terminalMapping: Terminal => Terminal) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def ssh: SSHClient = sshClient(ftpServer, username, path)
  def sftp(sshClient: SSHClient): SFTPClient = sftpClient(sshClient)

  def requestArrivals: ArrivalsFeedResponse = {
    val feedResponseTry = (for {
      sshClient <- Try(ssh)
      sftpClient <- Try(sftp(sshClient))
      responseTry = Try{
        Flights(arrivalsFromCsvContent(contentFromFileName(sftpClient, latestFileForPort(sftpClient, portCode)), terminalMapping))
      }
    } yield {
      sshClient.disconnect()
      sftpClient.close()
      responseTry
    }).flatten

    feedResponseTry match {
      case Success(a) =>
        ArrivalsFeedSuccess(a)
      case Failure(f) =>
        log.error(s"Failed to get flights from ACL: $f")
        ArrivalsFeedFailure(f.getMessage)
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
    ssh.setTimeout(0)
    ssh
  }

  def sftpClient(sshClient: SSHClient): SFTPClient = {
    sshClient.newSFTPClient
  }

  def latestFileForPort(sftp: SFTPClient, portCode: PortCode): String = {
    val portRegex = "([A-Z]{3})[SW][0-9]{2}_HOMEOFFICEROLL180_[0-9]{8}.zip".r
    val dateRegex = "[A-Z]{3}[SW][0-9]{2}_HOMEOFFICEROLL180_([0-9]{8}).zip".r

    val filesByDate = sftp
      .ls("/180_Days/").asScala
      .filter(_.getName match {
        case portRegex(pc) if pc == portCode.toString => true
        case _ => false
      })
      .sortBy(_.getName match {
        case dateRegex(date) => date
      })
      .reverse
    val oneHundredKbInBytes = 100000L
    val latestFileOver100KB: RemoteResourceInfo = filesByDate
      .find(_.getAttributes.getSize > oneHundredKbInBytes)
      .getOrElse(filesByDate.head)

    log.info(s"Latest File ${latestFileOver100KB}. Size: ${latestFileOver100KB.getAttributes.getSize}")

    latestFileOver100KB.getPath
  }

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
      log.info(s"ACL: ${arrivals.length} arrivals. Latest scheduled arrival: ${SDate(latestArrival.Scheduled).toLocalDateTimeString()} (${latestArrival.flightCode})")
    }
    arrivals
  }

  def contentFromFileName(sftp: SFTPClient, latestFileName: String): String = {
    val outputStream: ByteArrayOutputStream = new ByteArrayOutputStream()

    val file: InMemoryDestFile = new InMemoryDestFile {
      def getOutputStream: ByteArrayOutputStream = outputStream
    }

    sftp.get(latestFileName, file)

    val zis: ZipInputStream = new ZipInputStream(new ByteArrayInputStream(outputStream.toByteArray))

    val csvContent: String = unzipStream(zis).toList.head

    dropFileNameFromContent(csvContent)
  }

  def dropFileNameFromContent(content: String): String = content
    .split("\n")
    .drop(1)
    .mkString("\n")

  def unzipStream(zipInputStream: ZipInputStream): Seq[String] = {
    try {
      unzipAllFilesInStream(zipInputStream).toList
    } finally {
      zipInputStream.close()
    }
  }

  def unzipAllFilesInStream(unzippedStream: ZipInputStream): Stream[String] = {
    unzipAllFilesInStream(unzippedStream, Option(unzippedStream.getNextEntry))
  }

  def unzipAllFilesInStream(unzippedStream: ZipInputStream, zipEntryOption: Option[ZipEntry]): Stream[String] = {
    zipEntryOption match {
      case None => Stream.empty
      case Some(_) =>
        val entry: String = getZipEntry(unzippedStream)
        val maybeEntry1: Option[ZipEntry] = Option(unzippedStream.getNextEntry)
        entry #::
          unzipAllFilesInStream(unzippedStream, maybeEntry1)
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

  def dateAndTimeToDateTimeIso(date: String, time: String): String = s"${date}T${formatTimeToIso(time)}"

  def formatTimeToIso(time: String): String = f"${time.toInt}%04d".splitAt(2) match {
    case (hour, minute) => s"$hour:$minute:00Z"
  }

  def aclFieldsToArrival(fields: List[String], aclToPortTerminal: Terminal => Terminal): Try[Arrival] = {
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
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = if (maxPax == 0) None else Option(maxPax),
        ActPax = if (actPax == 0) None else Option(actPax),
        TranPax = None,
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = fields(AclColIndex.Airport),
        Terminal = portTerminal,
        rawICAO = fields(AclColIndex.FlightNumber),
        rawIATA = fields(AclColIndex.FlightNumber),
        Origin = fields(AclColIndex.Origin),
        Scheduled = SDate(dateAndTimeToDateTimeIso(fields(AclColIndex.Date), fields(AclColIndex.Time))).millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(shared.AclFeedSource)
      )
    }
  }

  object AclColIndex {

    val allFields: Map[String, Int] = List(
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
    val FlightType: Int = allFields("ST")
  }

  def aclToPortMapping(portCode: PortCode): Terminal => Terminal = portCode match {
    case PortCode("LGW") => (tIn: Terminal) => Map[Terminal, Terminal](T1 -> S, T2 -> N).getOrElse(tIn, tIn)
    case PortCode("EDI") => (tIn: Terminal) => Map[Terminal, Terminal](T1 -> A1).getOrElse(tIn, tIn)
    case PortCode("LCY") => (tIn: Terminal) => Map[Terminal, Terminal](ACLTER -> T1).getOrElse(tIn, tIn)
    case _ => (tIn: Terminal) => tIn
  }
}
