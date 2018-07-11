package drt.server.feeds.lhr.forecast

import java.util.TimeZone

import drt.shared.SDateLike
import info.folone.scala.poi._
import info.folone.scala.poi.impure._
import org.apache.poi.ss.usermodel.DateUtil
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.europeLondonId


case class LHRForecastFlightRow(
                                 scheduledDate: SDateLike,
                                 flightCode: String = "",
                                 origin: String = "",
                                 internationalDomestic: String = "",
                                 totalPax: Int = 0,
                                 transferPax: Int = 0,
                                 terminal: String
                               )

object LHRForecastXLSExtractor {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(xlsFilePath: String): List[LHRForecastFlightRow] = {
    val lhrWorkbook: Workbook = load(xlsFilePath)

    log.info(s"Extracting forecast flights from XLS Workbook located at $xlsFilePath")

    val arrivals = for {
      terminal <- List("T2", "T3", "T4", "T5")
      row <- lhrWorkbook.sheetMap(terminal + " Arr").rows
      flightDate <- row.cells.find(cell => cell.index == 1 && cell.isInstanceOf[NumericCell]).map(c => c.asInstanceOf[NumericCell].data)
      number <- row.cells.find(cell => cell.index == 2 && cell.isInstanceOf[StringCell]).map(c => c.asInstanceOf[StringCell].data)
      airport <- row.cells.find(cell => cell.index == 3 && cell.isInstanceOf[StringCell]).map(c => c.asInstanceOf[StringCell].data)
      internationalDomestic <- row.cells.find(cell => cell.index == 4 && cell.isInstanceOf[StringCell]).map(c => c.asInstanceOf[StringCell].data)
      total <- row.cells.find(cell => cell.index == 5 && cell.isInstanceOf[NumericCell]).map(c => c.asInstanceOf[NumericCell].data)
      transfer <- row.cells.find(cell => cell.index == 7 && cell.isInstanceOf[NumericCell]).map(c => c.asInstanceOf[NumericCell].data)
    } yield {
      val scheduled = SDate(DateUtil.getJavaDate(flightDate, TimeZone.getTimeZone(europeLondonId)).getTime)
      LHRForecastFlightRow(scheduledDate = scheduled, flightCode = number, origin = airport, internationalDomestic = internationalDomestic, totalPax = total.toInt, transferPax = transfer.toInt, terminal)
    }

    log.info(s"Extracted ${arrivals.length} from XLS Workbook")
    arrivals
  }
}
