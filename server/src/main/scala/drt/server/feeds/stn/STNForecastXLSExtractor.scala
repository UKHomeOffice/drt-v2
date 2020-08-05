package drt.server.feeds.stn

import java.util.TimeZone

import drt.server.feeds.common.XlsExtractorUtil._
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared.{ArrivalStatus, ForecastFeedSource, PortCode, SDateLike}
import org.apache.poi.ss.usermodel.{Cell, DateUtil}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.util.{Success, Try}


case class STNForecastFlightRow(scheduledDate: SDateLike,
                                flightCode: String = "",
                                origin: String = "",
                                internationalDomestic: String = "",
                                maxPax: Int = 0,
                                totalPax: Int = 0
                               )

object STNForecastXLSExtractor {

  val log: Logger = LoggerFactory.getLogger(getClass)


  def apply(xlsFilePath: String): List[Arrival] = rows(xlsFilePath)
    .map(stnFieldsToArrival)
    .collect {
      case Success(arrival) => arrival
    }

  def rows(xlsFilePath: String): List[STNForecastFlightRow] = {

    log.info(s"Extracting STN forecast flights from XLS Workbook located at $xlsFilePath")

    val lgwWorkSheet = workbook(xlsFilePath)

    val sheet = sheetMapByIndex(3, lgwWorkSheet)
    val arrivalRows: Seq[STNForecastFlightRow] = for {
      rowNumber <- 2 to sheet.getLastRowNum
      row = sheet.getRow(rowNumber)
      if row.getCell(1) != null && row.getCell(1).getCellType != Cell.CELL_TYPE_BLANK
    } yield {
      val scheduledCell = numericCell(1, row).getOrElse(0.0)
      val carrierCodeCell = stringCell(2, row).getOrElse("")
      val flightNumberCell = stringCell(3, row).getOrElse("")
      val originCell = stringCell(4, row)
      val maxPaxCell = numericCell(5, row).getOrElse(0.0)
      val totalCell = numericCell(6, row).getOrElse(0.0)
      val internationalDomesticCell = stringCell(10, row)
      val scheduled = SDate(DateUtil.getJavaDate(scheduledCell, TimeZone.getTimeZone("UTC")).getTime)

      STNForecastFlightRow(scheduledDate = scheduled,
        flightCode = s"$carrierCodeCell$flightNumberCell",
        origin = originCell.getOrElse(""),
        internationalDomestic = internationalDomesticCell.getOrElse(""),
        totalPax = totalCell.toInt,
        maxPax = maxPaxCell.toInt
      )
    }

    log.info(s"Extracted ${arrivalRows.size} arrival rows from STN XLS Workbook")

    arrivalRows.toList.filter(_.internationalDomestic == "INTERNATIONAL")
  }


  def stnFieldsToArrival(flightRow: STNForecastFlightRow): Try[Arrival] = {
    Try {
      Arrival(
        Operator = None,
        Status = ArrivalStatus("Port Forecast"),
        Estimated = None,
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = Some(flightRow.maxPax),
        ActPax = if (flightRow.totalPax == 0) None else Option(flightRow.totalPax),
        TranPax = Some(0),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("STN"),
        Terminal = Terminal("T1"),
        rawICAO = flightRow.flightCode.replace(" ", ""),
        rawIATA = flightRow.flightCode.replace(" ", ""),
        Origin = PortCode(flightRow.origin),
        Scheduled = flightRow.scheduledDate.millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource)
      )
    }
  }
}