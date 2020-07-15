package drt.server.feeds.stn

import drt.server.feeds.common.XlsExtractorUtil._
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared.{ArrivalStatus, ForecastFeedSource, PortCode, SDateLike}
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.europeLondonId

import scala.util.{Success, Try}


case class STNForecastFlightRow(scheduledDate: SDateLike,
                                flightCode: String = "",
                                origin: String = "",
                                internationalDomestic: String = "",
                                totalPax: Int = 0,
                                transferPax: Int = 0,
                                terminal: String = ""
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

    val formatter = DateTimeFormat.forPattern("dd/MM/yyyy HH:mm")

    val lgwWorkSheet = workbook(xlsFilePath)

    val sheet = sheetMapByIndex(0, lgwWorkSheet)

    val arrivalRows: Seq[STNForecastFlightRow] = for {
      rowNumber <- 1 to sheet.getLastRowNum
      row = sheet.getRow(rowNumber)
      if row.getCell(0) != null
    } yield {
      val terminalCell = numericCell(0, row).map(t => s"T${t.toInt}")
      val scheduledCell = stringCell(1, row).getOrElse("")
      val flightNumberCell = stringCell(2, row)
      val airportCell = stringCell(3, row)
      val internationalDomesticCell = stringCell(4, row)
      val totalCell = numericCell(5, row).getOrElse(0.0)
      val transferPaxCell = numericCell(6, row).getOrElse(0.0)
      val scheduled = SDate(DateTime.parse(scheduledCell, formatter).getMillis, DateTimeZone.forID(europeLondonId))

      STNForecastFlightRow(scheduledDate = scheduled,
        flightCode = flightNumberCell.getOrElse(""),
        origin = airportCell.getOrElse(""),
        internationalDomestic = internationalDomesticCell.getOrElse(""),
        totalPax = totalCell.toInt,
        transferPax = transferPaxCell.toInt,
        terminalCell.getOrElse(""))
    }

    log.info(s"Extracted ${arrivalRows.size} arrival rows from STN XLS Workbook")

    arrivalRows.toList.filter(_.internationalDomestic == "I")
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