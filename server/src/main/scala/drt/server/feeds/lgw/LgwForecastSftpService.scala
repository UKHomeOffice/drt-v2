package drt.server.feeds.lgw

import drt.server.feeds.lgw.LgwForecastSftpService.sshClient
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.xfer.InMemoryDestFile
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import java.io.{ByteArrayOutputStream, OutputStream}
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}


object LgwForecastSftpService {
  def sshClient(ftpServer: String, port: Int, username: String, password: String): SSHClient = {
    val ssh = new SSHClient()
    ssh.loadKnownHosts()
    ssh.addHostKeyVerifier(new PromiscuousVerifier())
    ssh.connect(ftpServer, port)
    ssh.authPassword(username, password)
    ssh.setTimeout(5000)
    ssh
  }
}

case class LgwForecastSftpService(ftpServer: String, port: Int, username: String, password: String, pathPrefix: String) {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def ssh: SSHClient = sshClient(ftpServer, port, username, password)

  val latestContent: () => Option[String] = () => {
    Try(ssh.newSFTPClient()) match {
      case Success(client) =>
        val maybeContent = latestFileName(client).flatMap(file => contentForFile(client, file))
        client.close()
        ssh.close()
        maybeContent
      case Failure(t) =>
        log.error(s"[LgwForecastSftpService][latestContent] Failed to connect to sftp server: ${t.getMessage}")
        ssh.close()
        None
    }
  }

  private def contentForFile(sftp: SFTPClient, fileName: String): Option[String] = {
    log.info(s"[LgwForecastSftpService][contentForFile] Fetching file '$fileName' from sftp server: $ftpServer:$port")

    val outputStream: ByteArrayOutputStream = new ByteArrayOutputStream()

    val output: InMemoryDestFile = new InMemoryDestFile {
      override def getOutputStream: ByteArrayOutputStream = outputStream

      override def getLength: Long = outputStream.size()

      override def getOutputStream(append: Boolean): OutputStream = outputStream
    }

    Try(sftp.get(fileName, output)) match {
      case Success(_) => Some(outputStream.toString())
      case Failure(e) =>
        log.error(s"[LgwForecastSftpService][contentForFile] Failed to get file '$fileName' from sftp server: ${e.getMessage}")
        None
    }
  }

  private[lgw] def latestFileName(sftp: SFTPClient): Option[String] = {
    log.info(s"[LgwForecastSftpService][latestFileName] Fetching latest file name from sftp server: $ftpServer:$port")

    val legacyFileName: String = "-LGWArrForecast.csv"
    val newFilePattern: Regex = """LGWARRFORECAST-(\d{4})-(\d{2})-(\d{2})\.csv""".r

    val files = sftp.ls(pathPrefix).asScala.toList

    val newFiles = files.flatMap { f =>
      f.getName match {
        case newFilePattern(year, month, day) =>
          parseDate(year, month, day, f.getName).map(date => (date, f.getPath))
        case _ =>
          log.warn(s"[LgwForecastSftpService][latestFileName] Filename did not match pattern: ${f.getName}")
          None
      }
    }

    val legacyFiles = files.flatMap { f =>
      if (f.getName.contains(legacyFileName)) {
        Try(f.getName.split("-", 4)).toOption.flatMap {
          case Array(year, month, day, _) =>
            parseDate(year, month, day, f.getName).map(date => (date, f.getPath))
          case _ =>
            log.warn(s"[LgwForecastSftpService][latestFileName] Legacy filename parsing failed: ${f.getName}")
            None
        }
      } else None
    }

    newFiles.sortBy(_._1.millisSinceEpoch).reverse.headOption
      .orElse(legacyFiles.sortBy(_._1.millisSinceEpoch).reverse.headOption)
      .map(_._2)
      .orElse {
        log.warn(s"[LgwForecastSftpService][latestFileName] No suitable forecast file found in $pathPrefix on $ftpServer:$port")
        None
      }
  }

  private def parseDate(year: String, month: String, day: String, fileName: String): Option[SDateLike] = {
    Try(SDate(year.toInt, month.toInt, day.toInt)) match {
      case Success(date) => Some(date)
      case Failure(e) =>
        log.warn(s"[LgwForecastSftpService][parseDate] Date parsing failed for file '$fileName': ${e.getMessage}")
        None
    }
  }
}
