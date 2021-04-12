package feeds.acl

import drt.server.feeds.acl.AclFeed.latestFileForPort
import drt.shared._
import net.schmizz.sshj.sftp.{FileAttributes, RemoteResourceInfo, SFTPClient}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import scala.collection.JavaConverters._


class AclFeedMinFileSizeSpec extends Specification with Mockito {

  val sftp = mock[SFTPClient]

  "Given a latest ACL file that is greater than the byte threshold then it should be returned" >> {

    val mockLatest = mockFileWithSize(10000L, "MANS20_HOMEOFFICEROLL180_20200406.zip", "latest")
    val mockFile = mockFileWithSize(20000L, "MANS20_HOMEOFFICEROLL180_20200405.zip", "previous")

    sftp.ls("/180_Days/") returns List(mockLatest, mockFile).asJava

    val latestFilePathId = latestFileForPort(sftp, PortCode("MAN"), 100L)

    latestFilePathId === "latest"
  }

  "Given a latest ACL file that is lower than the byte threshold then it should return the latest file that is above the threshold" >> {

    val mockLatest = mockFileWithSize(10000L, "MANS20_HOMEOFFICEROLL180_20200406.zip", "latest")
    val mockFile = mockFileWithSize(20000L, "MANS20_HOMEOFFICEROLL180_20200405.zip", "previous")

    sftp.ls("/180_Days/") returns List(mockLatest, mockFile).asJava

    val latestFilePathId = latestFileForPort(sftp, PortCode("MAN"), 15000L)

    latestFilePathId === "previous"
  }

  def mockFileWithSize(bytes: Long, fileName: String, pathId: String): RemoteResourceInfo = {
    val mockFile = mock[RemoteResourceInfo]
    val mockAttributes = new FileAttributes.Builder()
      .withSize(bytes)
      .build()

    mockFile.getName returns fileName
    mockFile.getPath returns pathId
    mockFile.getAttributes returns mockAttributes
    mockFile
  }
}
