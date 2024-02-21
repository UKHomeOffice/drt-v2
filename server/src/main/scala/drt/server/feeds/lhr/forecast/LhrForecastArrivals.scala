package drt.server.feeds.lhr.forecast

import drt.server.feeds.Implicits._
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers, Predictions}
import uk.gov.homeoffice.drt.ports.ForecastFeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone

import scala.util.{Failure, Success, Try}

object LhrForecastArrivals {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(lines: Seq[String]): Seq[Arrival] = {
    lines
      .map(line => LhrForecastArrival(line))
      .filter {
        case Success(_) => true
        case Failure(t) =>
          log.info(s"couldn't parse: $t")
          false
      }
      .collect {
        case Success(a) => a
      }
  }
}

object LhrForecastArrival {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def terminal(fields: Seq[String]): Terminal = Terminal(s"T${fields.head}")

  def isArrival(fields: Seq[String]): Boolean = fields(1) == "A"

  def scheduledStr(fields: Seq[String]): String = {
    val date = fields(3)
    val time = fields(4)
    s"${date}T$time"
  }

  def scheduled(fields: Seq[String]): SDateLike = {
    SDate(scheduledStr(fields), europeLondonTimeZone)
  }

  def carrierCode(fields: Seq[String]): String = fields(5)

  def flightCode(fields: Seq[String]): String = fields(6).replace(" ", "")

  def origin(fields: Seq[String]): String = fields(7)

  def maxPax(fields: Seq[String]): Int = fields(12).toInt

  def paxTotal(fields: Seq[String]): Int = fields(13).toInt

  def paxTransit(fields: Seq[String]): Int = fields(14).toInt

  def apply(line: String): Try[Arrival] = {
    val fields = line.split(",")

    Try {
      val operator = carrierCode(fields)
      val maxPaxField = maxPax(fields)
      val actPax = paxTotal(fields)
      val transPax = paxTransit(fields)
      Arrival(
        Operator = operator,
        Status = "Forecast",
        Estimated = None,
        Predictions = Predictions(0L, Map()),
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = Option(maxPaxField),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = "LHR",
        Terminal = terminal(fields),
        rawIATA = flightCode(fields),
        rawICAO = flightCode(fields),
        Origin = origin(fields),
        Scheduled = scheduled(fields).millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource),
        PassengerSources = Map(ForecastFeedSource -> Passengers(Option(actPax), Option(transPax)))
      )
    } match {
      case Failure(t) =>
        log.info(s"Couldn't parse $line")
        Failure(t)
      case s => s
    }
  }
}
