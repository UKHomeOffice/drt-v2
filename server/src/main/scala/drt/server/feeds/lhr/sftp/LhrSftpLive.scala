package drt.server.feeds.lhr.sftp

import drt.server.feeds.SftpClientPasswordAuth

import scala.util.Try

case class LhrSftpLive(host: String, username: String, password: String) {
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
      val latestFileName = latestFile()

      val client = sftpClient

      val content = client.fileContent(latestFileName).split("\n").drop(1).mkString("\n")

      client.closeConnection()

      content
    }
  }
}