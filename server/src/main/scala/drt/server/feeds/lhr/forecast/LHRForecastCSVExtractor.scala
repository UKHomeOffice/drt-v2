package drt.server.feeds.lhr.forecast

import drt.server.feeds.lhr.LHRForecastFeed
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter, ISODateTimeFormat}
import org.slf4j.LoggerFactory
import services.SDate
import services.graphstages.Crunch

import scala.io.Source
import scala.util.{Failure, Success, Try}

object LHRForecastCSVExtractor {

  val log = LoggerFactory.getLogger(getClass)

  object FieldIndex {
    val terminal = 0
    val scheduleDate = 1
    val flightCode = 2
    val origin = 3
    val internationalOrDomestic = 4
    val totalPax = 5
    val pcpPax = 6
    val transferPax = 7
  }

  def apply(filePath: String) = {
    val file = Source.fromFile(filePath).getLines.mkString("\n")
    parse(file).map(LHRForecastFeed.lhrFieldsToArrival).collect {
      case Success(arrival) => arrival
    }
  }

  def parse(lhrCsvFixture: String) = {

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
          val pcpPax = rowFields(FieldIndex.pcpPax).toInt
          val transfer = rowFields(FieldIndex.transferPax).toInt

          LHRForecastFlightRow(scheduledDate, flightCode, origin, intOrDom, totalPax, transfer, terminal)
        } match {
          case Failure(e) =>
            log.info(s"Failed to parse CSV row $rowFields for LHR forecast CSV", e)
            Failure(e)
          case s => s
        }
      }).collect {
      case Success(a) if a.internationalDomestic == "I" => a
    }.toList
  }

  def asString(stringField: String) = stringField.replace("\"", "").trim()

  def asNumber(intField: String) = Try(asString(intField).toInt) match {
    case Success(number) => number
    case Failure(exception) =>
      log.warn(s"Invalid number value for numeric CSV field: $intField", exception)
      0
  }

  val ddMMYYYHHMMFormat: DateTimeFormatter = DateTimeFormat.forPattern("dd/MM/yyyy HH:mm")

  def asDate(dateField: String) = {
    val dateWithoutTZ = ddMMYYYHHMMFormat.parseDateTime(asString(dateField))
      .toString(ISODateTimeFormat.dateHourMinuteSecond)

    SDate(dateWithoutTZ, Crunch.europeLondonTimeZone)
  }
}
