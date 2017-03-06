package feeds

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import sys.process._

class LHRFeedSpec extends Specification {
  "Something" should {
    "do something" in {
      val username = ConfigFactory.load.getString("lhr_live_username")
      val password = ConfigFactory.load.getString("lhr_live_password")

      val curlCommand = Seq("/usr/local/bin/lhr-live-fetch-latest-feed.sh", "-u", username, "-p", password)

      val csvContents = curlCommand.!!

      println(csvContents)

      true
    }
  }
}
