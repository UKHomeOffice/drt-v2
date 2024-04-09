package drt.server.feeds.lhr

import akka.actor.typed
import akka.stream.scaladsl.Source
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.lhr.LHRFlightFeed.{emptyStringToOption, parseDateTime}
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVRecord}
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{FlightCode, LiveArrival}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDate

import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.{Failure, Success, Try}

case class LHRLiveFlight(
                          term: Terminal,
                          flightCode: String,
                          operator: String,
                          from: String,
                          airportName: String,
                          scheduled: DateTime,
                          estimated: Option[DateTime],
                          touchdown: Option[DateTime],
                          estChox: Option[DateTime],
                          actChox: Option[DateTime],
                          stand: Option[String],
                          maxPax: Option[Int],
                          actPax: Option[Int],
                          connPax: Option[Int]
                        ) {
  def flightId(): Int = {
    // flightcode,scheduled datetime and from port make the flight sufficiently unique for later parts of the pipeline
    // but we do not want to override the hashCode here because that would be surprising.
    val s = flightCode + scheduled.toString + from
    s.hashCode()
  }
}

case class LHRCsvException(originalLine: String, idx: Int, innerException: Throwable) extends Exception {
  override def toString: String = s"$originalLine : $idx $innerException"
}

case class LHRFlightFeed(csvRecords: Iterator[Int => String]) {

  def opt(s: String): Option[String] = emptyStringToOption(s, x => x)

  private def optDate(s: String): Option[DateTime] = emptyStringToOption(s, parseDateTime)

  private def optInt(s: String): Option[Int] = emptyStringToOption(s, _.toInt)

  private lazy val lhrFlights: Iterator[Try[LHRLiveFlight]] = {
    csvRecords.zipWithIndex.map { case (splitRow, lineNo) =>
      val t = Try {
        LHRLiveFlight(
          term = Terminal(s"T${splitRow(0)}"),
          flightCode = splitRow(1),
          operator = splitRow(2),
          from = splitRow(3),
          airportName = splitRow(4),
          scheduled = parseDateTime(splitRow(5)),
          estimated = optDate(splitRow(6)),
          touchdown = optDate(splitRow(7)),
          estChox = optDate(splitRow(8)),
          actChox = optDate(splitRow(9)),
          stand = opt(splitRow(10)),
          maxPax = optInt(splitRow(11)),
          actPax = optInt(splitRow(12)),
          connPax = optInt(splitRow(13)))
      }

      t match {
        case Success(s) => Success(s)
        case Failure(f) => Failure(LHRCsvException("", lineNo, f))
      }
    }
  }

  val walkTimeMinutes = 4

  private lazy val successfulFlights: Iterator[LHRLiveFlight] = lhrFlights.collect {
    case Success(s) => s
  }

  lazy val copiedToApiFlights: List[LiveArrival] =
    successfulFlights.map { flight =>
      val schDtIso = flight.scheduled.toDateTimeISO.toString()
      val (carrierCode, flightNumber, suffix) = FlightCode.flightCodeToParts(flight.flightCode)

      LiveArrival(
        operator = Option(flight.operator),
        maxPax = flight.maxPax,
        totalPax = flight.actPax,
        transPax = if (flight.actPax.isEmpty) None else flight.connPax,
        terminal = flight.term,
        voyageNumber = flightNumber.numeric,
        carrierCode = carrierCode.code,
        flightCodeSuffix = suffix.map(_.suffix),
        origin = flight.from,
        scheduled = SDate(schDtIso).millisSinceEpoch,
        estimated = flight.estimated.map(_.toDate.getTime),
        touchdown = flight.touchdown.map(_.toDate.getTime),
        estimatedChox = flight.estChox.map(_.toDate.getTime),
        actualChox = flight.actChox.map(_.toDate.getTime),
        status = "",
        gate = None,
        stand = flight.stand,
        runway = None,
        baggageReclaim = None,
      )
    }.toList
}

object LHRFlightFeed {

  def csvParserAsIteratorOfColumnGetter(csvString: String): Iterator[Int => String] = {
    val csv: CSVParser = CSVParser.parse(csvString, CSVFormat.DEFAULT)
    val csvGetters: Iterator[Int => String] = csv.iterator().asScala.map((l: CSVRecord) => (i: Int) => l.get(i))
    csvGetters
  }

  def emptyStringToOption[T](s: String, t: String => T): Option[T] = {
    if (s.isEmpty) None else Option(t(s))
  }

  val pattern: DateTimeFormatter = DateTimeFormat.forPattern("HH:mm dd/MM/YYYY")
  val log: Logger = LoggerFactory.getLogger(classOf[LHRFlightFeed])

  def parseDateTime(dateString: String): DateTime = pattern.parseDateTime(dateString)

  def apply(csvContentsProvider: () => Try[String],
            source: Source[FeedTick, typed.ActorRef[FeedTick]],
           ): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] =
    source.map { _ =>
      log.info(s"Requesting CSV")
      csvContentsProvider() match {
        case Success(csvContents) =>
          log.info(s"Got CSV content")
          val feedArrivals = LHRFlightFeed(csvParserAsIteratorOfColumnGetter(csvContents)).copiedToApiFlights
          ArrivalsFeedSuccess(feedArrivals, SDate.now())
        case Failure(exception) =>
          log.info(s"Failed to get data from LHR live", exception)
          ArrivalsFeedFailure(exception.toString, SDate.now())
      }
    }
}
