package drt.server.feeds.stn

import java.util.TimeZone

import drt.server.feeds.common.XlsExtractorUtil._
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared.{ArrivalStatus, ForecastFeedSource, PortCode, SDateLike}
import org.apache.poi.ss.usermodel.{Cell, DateUtil}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.util.{Failure, Success, Try}

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

    val sheet = sheetMapByName("Arrivals by flight", lgwWorkSheet)

    val headingIndexByNameMap: Map[String, Int] = headingIndexByName(sheet.getRow(1))

    val arrivalRowsTry: Seq[Try[STNForecastFlightRow]] = for {
      rowNumber <- 2 to sheet.getLastRowNum
      row = sheet.getRow(rowNumber)
      if row.getCell(1) != null && row.getCell(1).getCellType != Cell.CELL_TYPE_BLANK
    } yield {
      Try {
        val scheduledCell = tryNumericThenStringCellOption(headingIndexByNameMap("SCHEDULED TIME& DATE"), row)
        val carrierCodeCell = stringCellOption(headingIndexByNameMap("AIRLINE"), row).getOrElse("")
        val flightNumberCell = tryNumericThenStringCellOption(headingIndexByNameMap("FLIGHT NUMBER"), row)
        val originCell = stringCellOption(headingIndexByNameMap("DESTINATION / ORIGIN"), row)
        val maxPaxCell = tryNumericThenStringCellOption(headingIndexByNameMap("FLIGHT CAPACITY"), row)
        val totalCell = tryNumericThenStringCellOption(headingIndexByNameMap("FLIGHT FORECAST"), row)
        val internationalDomesticCell = stringCellOption(headingIndexByNameMap("TYPE"), row)
        val scheduled = SDate(DateUtil.getJavaDate(scheduledCell, TimeZone.getTimeZone("UTC")).getTime)

        STNForecastFlightRow(scheduledDate = scheduled,
          flightCode = s"$carrierCodeCell$flightNumberCell",
          origin = originCell.getOrElse(""),
          internationalDomestic = internationalDomesticCell.getOrElse(""),
          totalPax = totalCell.toInt,
          maxPax = maxPaxCell.toInt
        )
      }
    }

    val arrivalRows = arrivalRowsTry.zipWithIndex.toList.flatMap {
      case (Success(a), _) => Some(a)
      case (Failure(e), i) => log.warn(s"Invalid data on row ${i + 2} ${e.getMessage}", e)
        None
    }.filter(_.internationalDomestic == "INTERNATIONAL")

    log.info(s"Extracted ${arrivalRows.size} arrival rows from STN XLS Workbook")

    arrivalRows
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