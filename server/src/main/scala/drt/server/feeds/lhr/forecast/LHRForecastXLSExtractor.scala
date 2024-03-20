package drt.server.feeds.lhr.forecast


import drt.server.feeds.common.XlsExtractorUtil._
import drt.server.feeds.lhr.LHRForecastFeed
import org.apache.poi.ss.usermodel.DateUtil
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{Arrival, ForecastArrival}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonId
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import java.util.TimeZone
import scala.util.Success


case class LHRForecastFlightRow(scheduledDate: SDateLike,
                                 flightCode: String = "",
                                 origin: String = "",
                                 internationalDomestic: String = "",
                                 totalPax: Int = 0,
                                 transferPax: Int = 0,
                                 terminal: String)

object LHRForecastXLSExtractor {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(xlsFilePath: String): List[ForecastArrival] = rows(xlsFilePath)
    .map(LHRForecastFeed.lhrFieldsToArrival)
    .collect {
      case Success(arrival) => arrival
    }

  def rows(xlsFilePath: String): List[LHRForecastFlightRow] = {
    val lhrWorkbook = workbook(xlsFilePath)

    val numberOfSheet: Int = getNumberOfSheets(lhrWorkbook)

    val arrivalRows: Seq[LHRForecastFlightRow] = 1 until numberOfSheet flatMap { sheetNumber =>
      val sheet = sheetMapByIndex(sheetNumber, lhrWorkbook)
      for {
        rowNumber <- 3 to sheet.getLastRowNum
        row = sheet.getRow(rowNumber)
        if row.getCell(1) != null
      } yield {
        val scheduledCell = numericCellOption(1, row).getOrElse(0.0)
        val flightNumberCell = stringCellOption(2, row).getOrElse("")
        val airportCell = stringCellOption(3, row).getOrElse("")
        val internationalDomesticCell = stringCellOption(4, row).getOrElse("")
        val totalCell = numericCellOption(5, row).getOrElse(0.0)
        val transferPaxCell = numericCellOption(6, row).getOrElse(0.0)
        val scheduled = SDate(DateUtil.getJavaDate(scheduledCell, TimeZone.getTimeZone(europeLondonId)).getTime)
        val terminal = s"T${sheetNumber + 1}"

        LHRForecastFlightRow(scheduledDate = scheduled, flightCode = flightNumberCell, origin = airportCell, internationalDomestic = internationalDomesticCell, totalPax = totalCell.toInt, transferPax = transferPaxCell.toInt, terminal)
      }
    }

    log.info(s"Extracted ${arrivalRows.size} arrival rows from LHR XLS Workbook")

    arrivalRows.toList.filter(_.internationalDomestic == "INTERNATIONAL")
  }
}
