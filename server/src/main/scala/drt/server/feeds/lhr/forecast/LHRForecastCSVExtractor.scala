package drt.server.feeds.lhr.forecast

import drt.server.feeds.lhr.LHRForecastFeed
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter, ISODateTimeFormat}
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.io.Source
import scala.util.{Failure, Success, Try}

object LHRForecastCSVExtractor {
  private val log = LoggerFactory.getLogger(getClass)

  object FieldIndex {
    val terminal = 0
    val scheduleDate = 1
    val flightCode = 2
    val origin = 3
    val internationalOrDomestic = 4
    val totalPax = 5
    val transferPax = 7
  }

  def apply(filePath: String): Seq[Arrival] = {
    val source = Source.fromFile(filePath)
    val file = source.getLines().mkString("\n")
    source.close()
    parse(file).map(LHRForecastFeed.lhrFieldsToArrival).collect {
      case Success(arrival) => arrival
    }
  }

  def parse(lhrCsvFixture: String): Seq[LHRForecastFlightRow] = {
    val rows = lhrCsvFixture.split("\n").drop(1)

    rows
      .map(_.split(",").toSeq)
      .filter(_.size == 8)
      .map(rowFields => {

        Try {
          val terminal = s"T${asString(rowFields(FieldIndex.terminal))}"
          val scheduledDate = asDate(rowFields(FieldIndex.scheduleDate))
          val flightCode = asString(rowFields(FieldIndex.flightCode))
          val origin = asString(rowFields(FieldIndex.origin))
          val intOrDom = asString(rowFields(FieldIndex.internationalOrDomestic))
          val totalPax = rowFields(FieldIndex.totalPax).toInt
          val transfer = rowFields(FieldIndex.transferPax).toInt

          LHRForecastFlightRow(scheduledDate, flightCode, origin, intOrDom, totalPax, transfer, terminal)
        } match {
          case Failure(t) =>
            log.warn(s"Failed to parse CSV row $rowFields for LHR forecast CSV: ${t.getMessage}")
            Failure(t)
          case s => s
        }
      }).collect {
      case Success(a) if a.internationalDomestic == "I" => a
    }.toList
  }

  def asString(stringField: String): String = stringField.replace("\"", "").trim()

  def asNumber(intField: String): Int = Try(asString(intField).toInt) match {
    case Success(number) => number
    case Failure(exception) =>
      log.warn(s"Invalid number value for numeric CSV field: $intField", exception)
      0
  }

  val yyyyMMddHHmmssFormat: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")

  def asDate(dateField: String): SDateLike = {
    val dateWithoutTZ = yyyyMMddHHmmssFormat
      .parseDateTime(asString(dateField))
      .toString(ISODateTimeFormat.dateHourMinuteSecond)

    SDate(dateWithoutTZ, europeLondonTimeZone)
  }
}
