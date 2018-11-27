package drt.server.feeds.lhr.sftp

import drt.server.feeds.SftpClientPasswordAuth
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Try

case class LhrSftpLiveContentProvider(host: String, username: String, password: String) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def sftpClient: SftpClientPasswordAuth = SftpClientPasswordAuth(host, username, password)

  def latestFile(): String = {
    val client = sftpClient

    val filesSorted = client
      .ls("/")
      .filter(_.startsWith("LHR_DMNDDET_"))
      .sorted

    val latestFile = filesSorted.reverse.head

    client.closeConnection()

    latestFile
  }

  def latestContent: Try[String] = {
    Try {
      val csvFileName = latestFile()
      val client = sftpClient

      log.info(s"Latest LHR CSV: $csvFileName")

      val content = client.fileContent(csvFileName).split("\n").drop(1).mkString("\n")

      client.closeConnection()

      content
    }
  }
}