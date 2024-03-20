package feeds.lgw

import drt.server.feeds.lgw.{LgwForecastFeedCsvParser, LgwForecastSftpService}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.ForecastArrival
import uk.gov.homeoffice.drt.ports.Terminals.S

import scala.io.{BufferedSource, Source}


class LgwForecastFeedCsvSpec extends Specification {
  "I should be able to fetch file contents via sftp" >> {
    skipped("integration test with ssh server")

    val feedService = LgwForecastSftpService("some-host", 22, "some-username", "some-password", "/some/path/prefix")

    val client = feedService.ssh.newSFTPClient
    val feed = LgwForecastFeedCsvParser(feedService.latestContent)
    feed.parseLatestContent()
    client.close()

    success
  }

  "I should be able to parse arrivals from a GAL csv file" >> {
    val source: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource("LgwForecastFixture.csv").getPath)
    val contents = source.getLines().mkString("\n")
    val feed = LgwForecastFeedCsvParser(() => Option(contents))
    val flights = feed.parseCsv(contents)
    source.close()

    flights._1.size === 3
    flights._1.head === ForecastArrival(
      operator = None,
      voyageNumber = 1234,
      carrierCode = "AC",
      flightCodeSuffix = None,
      maxPax = Some(218),
      totalPax = Option(114),
      transPax = None,
      origin = "ALC",
      terminal = S,
      scheduled = 1675427700000L,
    )
  }
}
