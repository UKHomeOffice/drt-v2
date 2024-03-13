package feeds.lhr.sftp

import com.typesafe.config.ConfigFactory
import drt.server.feeds.lhr.sftp.LhrSftpLiveContentProvider
import org.specs2.mutable.Specification

class LhrLiveSpec extends Specification {
  "I can retrieve content from files in the sftp server" >> {
    skipped("Exploratory test to try new lhr sftp host")

    val config = ConfigFactory.load()
    val host = config.getString("feeds.lhr.sftp.live.host")
    val username = config.getString("feeds.lhr.sftp.live.username")
    val password = config.getString("feeds.lhr.sftp.live.password")
    val content: String = LhrSftpLiveContentProvider(host, username, password).latestContent.getOrElse("")

    content.nonEmpty
  }
}
