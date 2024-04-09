package drt.server.feeds.stn

import drt.server.feeds.common.XlsExtractorUtil._
import org.apache.poi.ss.usermodel.{CellType, DateUtil}
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{FlightCode, ForecastArrival}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import java.util.TimeZone
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

  def apply(xlsFilePath: String): List[ForecastArrival] = rows(xlsFilePath)
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
      if row.getCell(1) != null && row.getCell(1).getCellType != CellType.BLANK
    } yield {
      Try {
        val scheduledCell = tryNumericThenStringCellDoubleOption(headingIndexByNameMap("SCHEDULED TIME& DATE"), row)
        val carrierCodeCell = stringCellOption(headingIndexByNameMap("AIRLINE"), row).getOrElse("")
        val flightNumberCell = tryNumericThenStringCellIntOption(headingIndexByNameMap("FLIGHT NUMBER"), row)
        val originCell = stringCellOption(headingIndexByNameMap("DESTINATION / ORIGIN"), row)
        val maxPaxCell = tryNumericThenStringCellDoubleOption(headingIndexByNameMap("FLIGHT CAPACITY"), row)
        val totalCell = tryNumericThenStringCellDoubleOption(headingIndexByNameMap("FLIGHT FORECAST"), row)
        val internationalDomesticCell = stringCellOption(headingIndexByNameMap("TYPE"), row)
        val scheduled = SDate(DateUtil.getJavaDate(scheduledCell, TimeZone.getTimeZone("UTC")).getTime)

        val flightNumber: String = if (flightNumberCell == 0) "" else flightNumberCell.toString
        STNForecastFlightRow(scheduledDate = scheduled,
          flightCode = s"$carrierCodeCell$flightNumber",
          origin = originCell.getOrElse(""),
          internationalDomestic = internationalDomesticCell.getOrElse(""),
          totalPax = totalCell.toInt,
          maxPax = maxPaxCell.toInt
        )
      }
    }

    val arrivalRows = arrivalRowsTry.zipWithIndex.toList.flatMap {
      case (Success(a), _) =>
        val inLondonEuropeTz = a.copy(scheduledDate = SDate(s"${a.scheduledDate.toISODateOnly}T${a.scheduledDate.toHoursAndMinutes}", europeLondonTimeZone))
        Some(inLondonEuropeTz)
      case (Failure(e), i) => log.warn(s"Invalid data on row ${i + 3} ${e.getMessage}", e)
        None
    }.filter(_.internationalDomestic == "INTERNATIONAL")

    log.info(s"Extracted ${arrivalRows.size} arrival rows from STN XLS Workbook")

    arrivalRows
  }


  private def stnFieldsToArrival(flightRow: STNForecastFlightRow): Try[ForecastArrival] = {
    val totalPax = if (flightRow.totalPax == 0) None else Option(flightRow.totalPax)
    val (carrierCode, voyageNumber, _) = FlightCode.flightCodeToParts(flightRow.flightCode.replace(" ", ""))
    Try {
      ForecastArrival(
        operator = None,
        maxPax = Some(flightRow.maxPax),
        totalPax = totalPax,
        transPax = None,
        terminal = Terminal("T1"),
        voyageNumber = voyageNumber.numeric,
        carrierCode = carrierCode.code,
        flightCodeSuffix = None,
        origin = flightRow.origin,
        scheduled = flightRow.scheduledDate.millisSinceEpoch,
      )
    }
  }
}
