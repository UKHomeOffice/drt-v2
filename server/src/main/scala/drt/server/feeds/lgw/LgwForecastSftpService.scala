package drt.server.feeds.lgw

import drt.server.feeds.lgw.LgwForecastSftpService.sshClient
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.xfer.InMemoryDestFile
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.time.SDate

import java.io.ByteArrayOutputStream
import scala.jdk.CollectionConverters.CollectionHasAsScala
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
        log.error(s"Failed to connect to sftp server: ${t.getMessage}")
        ssh.close()
        None
    }
  }

  private def contentForFile(sftp: SFTPClient, fileName: String): Option[String] = {
    log.info(s"Fetching file '$fileName' from sftp server: $ftpServer:$port")

    val outputStream: ByteArrayOutputStream = new ByteArrayOutputStream()

    val output: InMemoryDestFile = new InMemoryDestFile {
      def getOutputStream: ByteArrayOutputStream = outputStream
    }

    Try(sftp.get(fileName, output)) match {
      case Success(_) => Some(outputStream.toString())
      case Failure(e) =>
        log.error(s"Failed to get file '$fileName' from sftp server: ${e.getMessage}")
        None
    }
  }

  private def latestFileName(sftp: SFTPClient): Option[String] = {
    log.info(s"Fetching latest file name from sftp server: $ftpServer:$port")

    sftp
      .ls(pathPrefix).asScala
      .filter(_.getName.contains("-LGWArrForecast.csv"))
      .map { f =>
        Try(f.getName.split("-", 4)).map {
          case Array(year, month, day, _) =>
            (SDate(year.toInt, month.toInt, day.toInt), f.getPath)
        }
      }
      .collect {
        case Success(someDate) => someDate
      }
      .toList
      .sortBy(_._1.millisSinceEpoch)
      .reverse
      .headOption
      .map(_._2)
  }
}
