package server.feeds.acl

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.{ZipEntry, ZipInputStream}

import drt.shared.Arrival
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.xfer.InMemoryDestFile
import passengersplits.core.ZipUtils.UnzippedFileContent
import services.SDate

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.{Success, Try}


object AclFeed {

  def sftpClient(ftpServer: String, username: String, path: String): SFTPClient = {
    val ssh = new SSHClient()
    ssh.loadKnownHosts()
    ssh.connect(ftpServer)
    ssh.authPublickey(username, path)
    ssh.setTimeout(0)

    ssh.newSFTPClient
  }

  def latestFileForPort(sftp: SFTPClient, portCode: String): String = {
    val portRegex = "([A-Z]{3})[SW][0-9]{2}_HOMEOFFICEROLL180_[0-9]{8}.zip".r
    val dateRegex = "[A-Z]{3}[SW][0-9]{2}_HOMEOFFICEROLL180_([0-9]{8}).zip".r

    val latestFile = sftp
      .ls("/180_Days/").asScala
      .filter(_.getName match {
        case portRegex(pc) if pc == portCode => true
        case _ => false
      })
      .sortBy(_.getName match {
        case dateRegex(date) => date
      })
      .reverse.head
    latestFile.getPath
  }

  def arrivalsFromCsvContent(csvContent: String): List[Arrival] = {
    val flightEntries = csvContent
      .split("\n")
      .drop(1)

    val arrivalEntries = flightEntries
      .map(_.split(",").toList)
      .filter(_.length == 30)
      .filter(_ (3) == "A")

    arrivalEntries
      .map(aclFieldsToArrival)
      .collect { case Success(a) => a }
      .toList
  }

  def contentFromFileName(sftp: SFTPClient, latestFileName: String): String = {
    val outputStream: ByteArrayOutputStream = new ByteArrayOutputStream()

    val file = new InMemoryDestFile {
      def getOutputStream: ByteArrayOutputStream = outputStream
    }

    sftp.get(latestFileName, file)

    val zis: ZipInputStream = new ZipInputStream(new ByteArrayInputStream(outputStream.toByteArray))

    val csvContent: String = unzipStream(zis).map(_.content).toList.head
    csvContent
  }

  def unzipStream(zipInputStream: ZipInputStream): Seq[UnzippedFileContent] = {
    try {
      unzipAllFilesInStream(zipInputStream).toList
    } finally {
      zipInputStream.close()
    }
  }

  def unzipAllFilesInStream(unzippedStream: ZipInputStream): Stream[UnzippedFileContent] = {
    unzipAllFilesInStream(unzippedStream, Option(unzippedStream.getNextEntry))
  }

  def unzipAllFilesInStream(unzippedStream: ZipInputStream, zipEntryOption: Option[ZipEntry]): Stream[UnzippedFileContent] = {
    zipEntryOption match {
      case None => Stream.empty
      case Some(zipEntry) =>
        val name: String = zipEntry.getName
        val entry: String = getZipEntry(unzippedStream)
        val maybeEntry1: Option[ZipEntry] = Option(unzippedStream.getNextEntry)
        UnzippedFileContent(name, entry) #::
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

  def dateAndTimeToDateTimeIso(date: String, time: String): String = {
    s"${date}T${formatTimeToIso(time)}"
  }

  def formatTimeToIso(time: String): String = f"${time.toInt}%04d".splitAt(2) match {
    case (hour, minute) => s"$hour:$minute:00Z"
  }

  def aclFieldsToArrival(fields: List[String]): Try[Arrival] = {
    Try {
      Arrival(
        Operator = fields(13),
        Status = "Forecast",
        EstDT = "",
        ActDT = "",
        EstChoxDT = "",
        ActChoxDT = "",
        Gate = "",
        Stand = "",
        MaxPax = fields(20).toInt,
        ActPax = (fields(20).toInt * fields(29).toDouble).round.toInt,
        TranPax = 0,
        RunwayID = "",
        BaggageReclaimId = "",
        FlightID = (fields(28) + fields(5) + fields(25) + fields(16)).hashCode,
        AirportID = fields(2),
        Terminal = s"T${
          fields(24).take(1)
        }",
        rawICAO = fields(28),
        rawIATA = fields(28),
        Origin = fields(16),
        SchDT = dateAndTimeToDateTimeIso(fields(5), fields(25)),
        Scheduled = SDate(dateAndTimeToDateTimeIso(fields(5), fields(25))).millisSinceEpoch,
        PcpTime = 0,
        None
      )
    }
  }
}
