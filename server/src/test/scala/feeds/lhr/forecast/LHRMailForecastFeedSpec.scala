package feeds.lhr.forecast

import java.io.File

import com.typesafe.config.ConfigFactory
import drt.server.feeds.lhr.forecast.{LHRForecastEmail, LHRForecastFlightRow, LHRForecastXLSExtractor}
import org.specs2.mutable.Specification
import services.SDate


class LHRMailForecastFeedSpec extends Specification {

  "When processing the LHR forecast feed via email" >> {
    "Given connection details, I should be able to connect to the server" >> {
      skipped("Integration test for connecting to mailserver (requires ssh tunnel to run locally)")

      val imapServer = ConfigFactory.load().getString("lhr.forecast.imap_server")
      val imapPort = ConfigFactory.load().getInt("lhr.forecast.imap_port")
      val imapUsername = ConfigFactory.load().getString("lhr.forecast.imap_username")
      val imapPassword = ConfigFactory.load().getString("lhr.forecast.imap_password")
      val imapFromAddress = ConfigFactory.load().getString("lhr.forecast.from_address")
      val latest = LHRForecastEmail(imapServer, imapUsername, imapPassword, imapFromAddress, imapPort).maybeLatestForecastFile

      latest.isDefined === true
    }

    "Given an excel file with the LHR forecast format then I should get forecast flights for each terminal" >> {
      val path = getClass.getClassLoader.getResource("LHR_Forecast_Fixture.xlsx").getPath

      val result = LHRForecastXLSExtractor(path).toSet

      val expected = Set(
        LHRForecastFlightRow(SDate("2018-02-07T06:40:00Z"), "UA 0958", "ORD", "INTERNATIONAL", 100, 80, "T2"),
        LHRForecastFlightRow(SDate("2018-02-07T06:45:00Z"), "QF 0001", "SYD", "INTERNATIONAL", 180, 100, "T3"),
        LHRForecastFlightRow(SDate("2018-02-07T06:45:00Z"), "EY 0011", "AUH", "INTERNATIONAL", 100, 70, "T4"),
        LHRForecastFlightRow(SDate("2018-02-07T07:00:00Z"), "BA 0246", "GRU", "INTERNATIONAL", 100, 50, "T5"),
        LHRForecastFlightRow(SDate("2018-02-07T06:45:00Z"), "BA 0294", "ORD", "INTERNATIONAL", 100, 60, "T5")
      )

      result === expected
    }
  }
}
