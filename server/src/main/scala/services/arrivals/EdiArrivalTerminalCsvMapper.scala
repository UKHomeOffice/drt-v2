package services.arrivals

import java.net.URL

import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared.{FlightCode, MonthStrings}
import org.apache.commons.csv.{CSVFormat, CSVParser}

import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.Try


object EdiArrivalTerminalCsvMapper {

  val monthHeaders: Seq[String] = MonthStrings.months

  def apply(csvUrl: URL): Try[Map[String, Map[String, Terminal]]] = Try {
    val csvSource = Source.fromURL(csvUrl)
    val csvString: String = csvSource.mkString
    csvSource.close()
    csvStringToMap(csvString)
  }

  def csvStringToMap(csvString: String): Map[String, Map[String, Terminal]] = {
    val csv = CSVParser.parse(csvString, CSVFormat.DEFAULT.withFirstRecordAsHeader())
    csv.iterator().asScala.map(row => {
      FlightCode(row.get("code")).toString -> monthHeaders.map(m => m -> Terminal(row.get(m))).toMap
    }).toMap
  }
}
