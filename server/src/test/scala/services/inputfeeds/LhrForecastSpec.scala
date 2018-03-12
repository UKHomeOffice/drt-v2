package services.inputfeeds

import drt.shared.{Arrival, SDateLike}
import org.joda.time.DateTimeZone
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.Specification
import services.SDate

import scala.io.Source
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


class LhrForecastSpec extends Specification {

  "Given a line from the CSV of the new LHR forecast feed " +
    "When I ask for a parsed Arrival " +
    "Then I should see an Arrival representing the CSV data " >> {
    val csvContent =
      """Terminal,Arr / Dep,DOW,Scheduled Date,Scheduled Time,Prefix,Flight No,Orig / Dest,Orig / Dest Market,Last / Next,Last / Next Market,Aircraft,Capacity,Total Pax,Transfer Pax,Direct Pax,Transfer Demand,Direct Demand
        |3,A,Thu,2018-02-22,04:45:00,BA,BA 0058,CPT,Africa,CPT,Africa,744,337,333,142,191,131,201""".stripMargin

    val arrivalLines = csvContent.split("\n").drop(1)

    val arrival = LhrForecastArrivals(arrivalLines).head

    val expected = Arrival("BA", "Forecast", "", "", "", "", "", "", 337, 333, 142, "", "", 0, "", "T3", "BA0058", "BA0058", "CPT", "2018-02-22T04:45:00", 1519274700000L, 0, None)

    arrival === expected
  }

  "Given an entire CSV " +
    "When I ask for the Arrivals " +
    "Then I should see all the valid lines from the CSV as Arrivals" >> {
    skipped("exploratory")
    val filename = "/tmp/lhr-forecast.csv"
    val arrivalTries = Source.fromFile(filename).getLines.toSeq.map(LhrForecastArrival(_))
    val totalEntries = arrivalTries.length
    val arrivals = arrivalTries
        .filter {
          case Success(_) => true
          case Failure(t) =>
            println(s"failed: $t")
            false
        }
      .collect {
        case Success(a) => a
      }

    val totalArrivals = arrivals.length

    println(s"parsed $totalArrivals from $totalEntries")

    true
  }
}