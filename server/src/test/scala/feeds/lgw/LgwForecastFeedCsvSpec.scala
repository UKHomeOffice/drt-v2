package feeds.lgw

import drt.shared.CrunchApi.MillisSinceEpoch
import feeds.lgw.LgwForecastFeedCsv.sshClient
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.xfer.InMemoryDestFile
import org.apache.commons.csv.{CSVFormat, CSVParser}
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, FlightCode, FlightCodeSuffix, VoyageNumber, VoyageNumberLike}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.{S, Terminal}
import uk.gov.homeoffice.drt.time.SDate

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import scala.io.{BufferedSource, Source}
import scala.jdk.CollectionConverters.{CollectionHasAsScala, IteratorHasAsScala}
import scala.util.{Failure, Success, Try}

case class ForecastArrival(carrierCode: CarrierCode,
                           flightNumber: VoyageNumberLike,
                           maybeFlightCodeSuffix: Option[FlightCodeSuffix],
                           origin: PortCode,
                           terminal: Terminal,
                           scheduled: MillisSinceEpoch,
                           totalPax: Int,
                           transPax: Option[Int],
                           maxPax: Option[Int],
                          )

object LgwForecastFeedCsv {
  def sshClient(ftpServer: String, username: String, password: String): SSHClient = {
    val ssh = new SSHClient()
    ssh.loadKnownHosts()
    ssh.addHostKeyVerifier(new PromiscuousVerifier())
    ssh.connect(ftpServer)
    ssh.authPassword(username, password)
    ssh.setTimeout(0)
    ssh
  }

}

case class LgwForecastSftpService(ftpServer: String, username: String, password: String, pathPrefix: String) {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def ssh: SSHClient = sshClient(ftpServer, username, password)

  val latestContent: () => Option[String] = () => {
    val client = ssh.newSFTPClient()
    val maybeContent = latestFileName(client).flatMap(file => contentForFile(client, file))
    client.close()
    maybeContent
  }

  private def contentForFile(sftp: SFTPClient, fileName: String): Option[String] = {
    val outputStream: ByteArrayOutputStream = new ByteArrayOutputStream()

    val output: InMemoryDestFile = new InMemoryDestFile {
      def getOutputStream: ByteArrayOutputStream = outputStream
    }

    Try(sftp.get(fileName, output)) match {
      case Success(_) => Some(outputStream.toString(StandardCharsets.UTF_8))
      case Failure(e) =>
        log.error(s"Failed to get file '$fileName' from sftp server: ${e.getMessage}")
        None
    }
  }

  private def latestFileName(sftp: SFTPClient): Option[String] = {
    sftp
      .ls(pathPrefix).asScala
      .filter(_.getName.contains("-LGWArrForecast.csv"))
      .map { f =>
        Try(f.getName.split("-", 4)).map {
          case Array(year, month, day, _) =>
            (SDate(year.toInt, month.toInt, day.toInt), f.getPath)
        }
      }
      .collect {
        case Success(someDate) => someDate
      }
      .toList
      .sortBy(_._1.millisSinceEpoch)
      .reverse
      .headOption
      .map(_._2)
  }
}

case class LgwForecastFeedCsv(fetchContent: () => Option[String]) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def parseLatestFile(): Option[List[ForecastArrival]] = {
    fetchContent().map { content =>
      val flights = parseCsv(content)

      log.info(s"Parsed ${flights._1.size} arrivals from LGW forecast feed. ${flights._2} rows failed to parse.")

      flights._1
    }
  }

  def parseCsv(csvContent: String): (List[ForecastArrival], Int) = {
    val csv = CSVParser.parse(csvContent, CSVFormat.DEFAULT.withFirstRecordAsHeader())

    val rows = csv.iterator().asScala.toList

    val flights = rows
      .map { record =>
        Try {
          record.get("ArrDep") match {
            case "Arrival" =>
              val (carrierCode, voyageNumber, maybeSuffix) = FlightCode.flightCodeToParts(record.get("Flight Number"))
              val origin = PortCode(record.get("Airport Code"))
              val terminal = Terminal(record.get("Terminal") match {
                case "North" => "N"
                case "South" => "S"
                case _ => ""
              })
              val scheduledString = record.get("Date/Time")
              val scheduledDateAndTime = scheduledString.split(" ")
              val dateParts = scheduledDateAndTime(0).split("/")
              val timeParts = scheduledDateAndTime(1).split(":")
              val scheduled = SDate(dateParts(2).toInt, dateParts(1).toInt, dateParts(0).toInt, timeParts(0).toInt, timeParts(1).toInt).millisSinceEpoch
              val totalPax = record.get("POA PAX").toInt
              val transPax = None
              val maxPax = Option(record.get("Seats").toInt)
              Option(ForecastArrival(carrierCode, voyageNumber, maybeSuffix, origin, terminal, scheduled, totalPax, transPax, maxPax))
            case _ => None
          }
        }
      }
      .foldLeft((List[ForecastArrival](), 0)) {
        case ((arrivals, failedCount), next) =>
          next match {
            case Success(Some(fa)) => (fa :: arrivals, failedCount)
            case Success(None) => (arrivals, failedCount)
            case Failure(e) =>
              log.error(s"Failed to parse: ${e.getMessage}")
              (arrivals, failedCount + 1)
          }
      }
    flights
  }
}

class LgwForecastFeedCsvSpec extends Specification {
  "I should be able to fetch file contents via sftp" >> {
    skipped

    val feedService = LgwForecastSftpService("some-host", "some-username", "some-password", "/some/path/prefix")

    val client = feedService.ssh.newSFTPClient
    val feed = LgwForecastFeedCsv(feedService.latestContent)
    feed.parseLatestFile()
    client.close()

    success
  }

  "I should be able to parse arrivals from a GAL csv file" >> {
    val source: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource("LgwForecastFixture.csv").getPath)
    val contents = source.getLines().mkString("\n")
    val feed = LgwForecastFeedCsv(() => Option(contents))
    val flights = feed.parseCsv(contents)
    source.close()

    println(s"Failed to parse ${flights._2} rows")
    println(s"${flights._1.size} flights: ${flights._1}")

    flights._1.size === 3
    flights._1.head === ForecastArrival(CarrierCode("AC"), VoyageNumber(1234), None, PortCode("ALC"), S, 1675427700000L, 114, None, Some(218))
  }
}
