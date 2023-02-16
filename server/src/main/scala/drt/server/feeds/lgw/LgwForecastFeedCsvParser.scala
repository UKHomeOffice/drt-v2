package drt.server.feeds.lgw

import org.apache.commons.csv.{CSVFormat, CSVParser}
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{FlightCode, ForecastArrival, VoyageNumber}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDate

import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.{Failure, Success, Try}

case class LgwForecastFeedCsvParser(fetchContent: () => Option[String]) {
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
          val (carrierCode, voyageNumber, maybeSuffix) = FlightCode.flightCodeToParts(record.get("Flight Number"))
          (record.get("ArrDep"), voyageNumber) match {
            case ("Arrival", vn: VoyageNumber) =>
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
              val totalPax = Option(record.get("POA PAX").toInt)
              val transPax = None
              val maxPax = Option(record.get("Seats").toInt)
              Option(ForecastArrival(carrierCode, vn, maybeSuffix, origin, terminal, scheduled, totalPax, transPax, maxPax))
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
