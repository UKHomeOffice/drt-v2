package feeds

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import sys.process._

class LHRFeedSpec extends Specification {
  "Something" should {
    "do something" in {
j      val username = ConfigFactory.load.getString("lhr_live_username")
      val password = ConfigFactory.load.getString("lhr_live_password")

      val curlCommand = Seq("ssh", "jva01.dev.drt", "lhr-login", "-u", username, "-p", password)

      val cookie = curlCommand.!!

      println(cookie)

      true
    }
  }
}
