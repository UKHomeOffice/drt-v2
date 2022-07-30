package services.arrivals

import org.apache.commons.csv.{CSVFormat, CSVParser}
import uk.gov.homeoffice.drt.arrivals.FlightCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.MonthStrings

import java.net.URL
import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.Try


object EdiArrivalTerminalCsvMapper {

  def apply(csvUrl: URL): Try[Map[String, Map[String, Terminal]]] = Try {
    val csvSource = Source.fromURL(csvUrl)
    val csvString: String = csvSource.mkString
    csvSource.close()
    csvStringToMap(csvString)
  }

  def csvStringToMap(csvString: String): Map[String, Map[String, Terminal]] = {
    val csv = CSVParser.parse(csvString, CSVFormat.DEFAULT.withFirstRecordAsHeader())
    csv.iterator().asScala.map(row => {
      FlightCode(row.get("code")).toString -> MonthStrings.months.map(m => m -> Terminal(row.get(m))).toMap
    }).toMap
  }
}
