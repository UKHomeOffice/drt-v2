package drt.server.feeds.lhr.forecast

import drt.shared.{Arrival, SDateLike}
import org.joda.time.DateTimeZone
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.util.{Failure, Success, Try}

object LhrForecastArrivals {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(lines: Seq[String]): Seq[Arrival] = {
    lines
      .map(line => LhrForecastArrival(line))
      .filterNot {
        case Success(_) => false
        case Failure(t) =>
          log.info(s"couldn't parse: $t")
          true
      }
      .collect {
        case Success(a) => a
      }
  }
}

object LhrForecastArrival {
  def terminal(fields: Seq[String]): String = s"T${fields(0)}"

  def isArrival(fields: Seq[String]): Boolean = fields(1) == "A"

  def scheduledStr(fields: Seq[String]): String = {
    val date = fields(3)
    val time = fields(4)
    s"${date}T$time"
  }

  def scheduled(fields: Seq[String]): SDateLike = {
    SDate(scheduledStr(fields), DateTimeZone.forID("Europe/London"))
  }

  def carrierCode(fields: Seq[String]): String = fields(5)

  def flightCode(fields: Seq[String]): String = fields(6).replace(" ", "")

  def origin(fields: Seq[String]) = fields(7)

  def maxPax(fields: Seq[String]): Int = fields(12).toInt

  def paxTotal(fields: Seq[String]): Int = fields(13).toInt

  def paxTransit(fields: Seq[String]): Int = fields(14).toInt

  def apply(line: String): Try[Arrival] = {
    val fields = line.split(",")

    Try {
      Arrival(
        Operator = carrierCode(fields),
        Status = "Forecast",
        EstDT = "",
        ActDT = "",
        EstChoxDT = "",
        ActChoxDT = "",
        Gate = "",
        Stand = "",
        MaxPax = maxPax(fields),
        ActPax = paxTotal(fields),
        TranPax = paxTransit(fields),
        RunwayID = "",
        BaggageReclaimId = "",
        FlightID = 0,
        AirportID = "",
        Terminal = terminal(fields),
        rawIATA = flightCode(fields),
        rawICAO = flightCode(fields),
        Origin = origin(fields),
        SchDT = scheduledStr(fields),
        Scheduled = scheduled(fields).millisSinceEpoch,
        PcpTime = 0L,
        LastKnownPax = None
      )
    }
  }
}
