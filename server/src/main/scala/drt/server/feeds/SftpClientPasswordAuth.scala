package drt.server.feeds

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.xfer.InMemoryDestFile

import java.io.{ByteArrayOutputStream, OutputStream}
import scala.jdk.CollectionConverters.CollectionHasAsScala

case class SftpClientPasswordAuth(ftpServer: String, username: String, password: String) {
  val sshClient: SSHClient = {
    val ssh = new SSHClient()
    ssh.loadKnownHosts()
    ssh.connect(ftpServer)
    ssh.authPassword(username, password)
    ssh.setTimeout(5000)
    ssh
  }

  val sftpClient: SFTPClient = sshClient.newSFTPClient()

  def ls(path: String): List[String] = sftpClient.ls(path).asScala.map(_.getName).toList

  def fileContent(fileName: String): String = {
    val outputStream: ByteArrayOutputStream = new ByteArrayOutputStream()

    val file: InMemoryDestFile = new InMemoryDestFile {
      override def getOutputStream: ByteArrayOutputStream = outputStream

      override def getLength: Long = outputStream.size()

      override def getOutputStream(append: Boolean): OutputStream = outputStream
    }

    sftpClient.get(fileName, file)

    file.getOutputStream.toString
  }

  def closeConnection(): Unit = sshClient.close()
}
