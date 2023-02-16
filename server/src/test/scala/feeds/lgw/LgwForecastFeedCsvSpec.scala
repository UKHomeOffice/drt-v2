package feeds.lgw

import drt.server.feeds.lgw.{LgwForecastFeedCsvParser, LgwForecastSftpService}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, ForecastArrival, VoyageNumber}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.S

import scala.io.{BufferedSource, Source}


class LgwForecastFeedCsvSpec extends Specification {
  "I should be able to fetch file contents via sftp" >> {
    skipped("integration test with ssh server")

    val feedService = LgwForecastSftpService("some-host", "some-username", "some-password", "/some/path/prefix")

    val client = feedService.ssh.newSFTPClient
    val feed = LgwForecastFeedCsvParser(feedService.latestContent)
    feed.parseLatestFile()
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
    flights._1.head === ForecastArrival(CarrierCode("AC"), VoyageNumber(1234), None, PortCode("ALC"), S, 1675427700000L, Option(114), None, Some(218))
  }
}
