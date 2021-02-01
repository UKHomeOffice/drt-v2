package drt.server.feeds.lgw

import java.util.TimeZone

import drt.server.feeds.common.XlsExtractorUtil._
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared.{ArrivalStatus, ForecastFeedSource, PortCode, SDateLike}
import org.apache.poi.ss.usermodel.{Cell, DateUtil, Sheet}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.util.{Failure, Success, Try}


case class LGWForecastFlightRow(scheduledDate: SDateLike,
                                flightCode: String = "",
                                origin: String = "",
                                service: String = "",
                                arrivalOrDep: String = "",
                                internationalDomestic: String = "",
                                totalPax: Int = 0,
                                transferPax: Int = 0,
                                terminal: String
                               )

object LGWForecastXLSExtractor {

  val log: Logger = LoggerFactory.getLogger(getClass)


  def apply(xlsFilePath: String): List[Arrival] = rows(xlsFilePath)
    .map(lgwFieldsToArrival)
    .collect {
      case Success(arrival) => arrival
    }

  def rows(xlsFilePath: String): List[LGWForecastFlightRow] = {

    log.info(s"Extracting LGW forecast flights from XLS Workbook located at $xlsFilePath")
    val lgwWorkSheet = workbook(xlsFilePath)

    val sheet: Sheet = sheetMapByIndex(0, lgwWorkSheet)

    val headingIndexByNameMap: Map[String, Int] = headingIndexByName(sheet.getRow(0))

    val arrivalRowsTry: Seq[Try[LGWForecastFlightRow]] = for {
      rowNumber <- 1 to sheet.getLastRowNum
      row = sheet.getRow(rowNumber)
      if row.getCell(0) != null && row.getCell(1).getCellType != Cell.CELL_TYPE_BLANK
    } yield {
      Try {
        val terminalCell = "N"
        val flightDateCell = numericCellOption(headingIndexByNameMap("Date"), row)
        val flightTimeCell = numericCellOption(headingIndexByNameMap("Time (UTC)"), row)
        val flightNumberCell = stringCellOption(headingIndexByNameMap("FlightNo"), row)
        val airportCell = stringCellOption(headingIndexByNameMap("Airport"), row)
        val serviceCell = stringCellOption(headingIndexByNameMap("Service"), row)
        val arrivalOrDepCell = stringCellOption(headingIndexByNameMap("ArrDep"), row)
        val internationalDomesticCell = stringCellOption(headingIndexByNameMap("Dom/Int"), row)
        val totalCell = tryNumericThenStringCellIntOption(headingIndexByNameMap("Forecast Pax"), row)

        val scheduledCell = SDate(DateUtil.getJavaDate(flightDateCell.getOrElse(0.0) + flightTimeCell.getOrElse(0.0), TimeZone.getTimeZone("UTC")).getTime)

        LGWForecastFlightRow(scheduledDate = scheduledCell,
          flightCode = flightNumberCell.getOrElse(""),
          origin = airportCell.getOrElse(""),
          service = serviceCell.getOrElse(""),
          arrivalOrDep = arrivalOrDepCell.getOrElse(""),
          internationalDomestic = internationalDomesticCell.getOrElse(""),
          totalPax = totalCell,
          transferPax = 0,
          terminalCell)
      }
    }

    val arrivalRows = arrivalRowsTry.zipWithIndex.toList.flatMap {
      case (Success(a), _) => Some(a)
      case (Failure(e), i) => log.warn(s"Invalid data on row ${i + 2} ${e.getMessage}", e)
        None
    }.filter(_.service == "Passenger").filter(_.arrivalOrDep == "A").filter(_.internationalDomestic == "I")

    log.info(s"Extracted ${arrivalRows.size} arrival rows from LGW XLS Workbook")
    arrivalRows

  }


  def lgwFieldsToArrival(flightRow: LGWForecastFlightRow): Try[Arrival] = {
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
        MaxPax = None,
        ActPax = if (flightRow.totalPax == 0) None else Option(flightRow.totalPax),
        TranPax = if (flightRow.totalPax == 0) None else Option(flightRow.transferPax),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("LGW"),
        Terminal = Terminal(flightRow.terminal),
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