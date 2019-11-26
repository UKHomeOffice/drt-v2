package services.inputfeeds

import drt.server.feeds.lhr.forecast.{LhrForecastArrival, LhrForecastArrivals}
import drt.shared.Terminals.T3
import drt.shared.{Arrival, ForecastFeedSource}
import org.specs2.mutable.Specification
import services.SDate

import scala.io.Source
import scala.util.{Failure, Success}


class LhrForecastSpec extends Specification {

  "Given a line from the CSV of the new LHR forecast feed " +
    "When I ask for a parsed Arrival " +
    "Then I should see an Arrival representing the CSV data " >> {
    val csvContent =
      """Terminal,Arr / Dep,DOW,Scheduled Date,Scheduled Time,Prefix,Flight No,Orig / Dest,Orig / Dest Market,Last / Next,Last / Next Market,Aircraft,Capacity,Total Pax,Transfer Pax,Direct Pax,Transfer Demand,Direct Demand
        |3,A,Thu,2018-02-22,04:45:00,BA,BA 0058,CPT,Africa,CPT,Africa,744,337,333,142,191,131,201""".stripMargin

    val arrivalLines = csvContent.split("\n").drop(1)

    val arrival = LhrForecastArrivals(arrivalLines).head

    val expected = Arrival(Operator = Some("BA"), Status = "Forecast", Estimated = None, Actual = None,
      EstimatedChox = None, ActualChox = None, Gate = None, Stand = None, MaxPax = Some(337),
      ActPax = Some(333), TranPax = Some(142), RunwayID = None, BaggageReclaimId = None, AirportID = "LHR", Terminal = T3,
      rawICAO = "BA0058", rawIATA = "BA0058", Origin = "CPT", FeedSources = Set(ForecastFeedSource),
      Scheduled = SDate("2018-02-22T04:45:00").millisSinceEpoch, PcpTime = None)

    arrival === expected
  }

  "Given an entire CSV " +
    "When I ask for the Arrivals " +
    "Then I should see all the valid lines from the CSV as Arrivals" >> {
    skipped("exploratory")
    val filename = "/tmp/lhr-forecast.csv"
    val arrivalTries = Source.fromFile(filename).getLines.toSeq.drop(1).map(LhrForecastArrival(_))
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
