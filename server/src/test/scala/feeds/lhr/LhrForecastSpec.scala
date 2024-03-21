package feeds.lhr

import drt.server.feeds.lhr.forecast.LhrForecastArrivals
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.T3
import uk.gov.homeoffice.drt.ports.{ForecastFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDate


class LhrForecastSpec extends Specification {

  "Given a line from the CSV of the new LHR forecast feed " +
    "When I ask for a parsed Arrival " +
    "Then I should see an Arrival representing the CSV data " >> {
    val csvContent =
      """Terminal,Arr / Dep,DOW,Scheduled Date,Scheduled Time,Prefix,Flight No,Orig / Dest,Orig / Dest Market,Last / Next,Last / Next Market,Aircraft,Capacity,Total Pax,Transfer Pax,Direct Pax,Transfer Demand,Direct Demand
        |3,A,Thu,2018-02-22,04:45:00,BA,BA 0058,CPT,Africa,CPT,Africa,744,337,333,142,191,131,201""".stripMargin

    val arrivalLines = csvContent.split("\n").drop(1)

    val arrival = LhrForecastArrivals(arrivalLines.toIndexedSeq).head

    val expected = Arrival(
      Operator = Option(Operator("BA")),
      Status = ArrivalStatus("Forecast"),
      Estimated = None,
      Predictions = Predictions(0L, Map()),
      Actual = None,
      EstimatedChox = None,
      ActualChox = None,
      Gate = None,
      Stand = None,
      MaxPax = Option(337),
      RunwayID = None,
      BaggageReclaimId = None,
      AirportID = PortCode("LHR"),
      Terminal = T3,
      rawICAO = "BA0058",
      rawIATA = "BA0058",
      Origin = PortCode("CPT"),
      FeedSources = Set(ForecastFeedSource),
      Scheduled = SDate("2018-02-22T04:45:00").millisSinceEpoch,
      PcpTime = None,
      PassengerSources = Map(ForecastFeedSource -> Passengers(Option(333), Option(142))))

    arrival === expected
  }

  "Given a forecast feed item with 0 passengers for max, direct and transfer. " +
    "When I ask for a parsed Arrival " +
    "Then I should see 0 in ActPax and TransPax " >> {
    val csvContent =
      """Terminal,Arr / Dep,DOW,Scheduled Date,Scheduled Time,Prefix,Flight No,Orig / Dest,Orig / Dest Market,Last / Next,Last / Next Market,Aircraft,Capacity,Total Pax,Transfer Pax,Direct Pax,Transfer Demand,Direct Demand
        |3,A,Thu,2018-02-22,04:45:00,BA,BA 0058,CPT,Africa,CPT,Africa,744,0,0,0,191,131,201""".stripMargin

    val arrivalLines = csvContent.split("\n").drop(1)

    val arrival: Arrival = LhrForecastArrivals(arrivalLines.toIndexedSeq).head
    val actMaxTran = arrival.PassengerSources.get(ForecastFeedSource)
      .map(a => (arrival.MaxPax, a.actual, a.transit))
      .getOrElse((None, None, None))

    val expected = (Some(0), Some(0), Some(0))

    actMaxTran === expected
  }
}
