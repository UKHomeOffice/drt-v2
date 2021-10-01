package services.`export`

import akka.stream.scaladsl.{Sink, Source}
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.FlightsWithSplits
import uk.gov.homeoffice.drt.ports.Terminals.T1
import drt.shared._
import services.SDate
import services.crunch.CrunchTestLike
import services.exports.flights.templates.{CedatFlightsExport, FlightsWithSplitsWithActualApiExport, FlightsWithSplitsWithActualApiExportImpl, FlightsWithSplitsWithoutActualApiExport, FlightsWithSplitsWithoutActualApiExportImpl}

import scala.concurrent.Await
import scala.concurrent.duration._

class StreamingFlightsExportSpec extends CrunchTestLike {

  import controllers.ArrivalGenerator.arrival

  val flightWithAllTypesOfAPISplit: ApiFlightWithSplits = ApiFlightWithSplits(
    arrival(
      iata = "SA324",
      icao = "SA0324",
      schDt = "2017-01-01T20:00:00Z",
      actPax = Option(98),
      apiPax = Option(100),
      maxPax = Option(100),
      terminal = T1,
      origin = PortCode("JHB"),
      operator = Option(Operator("SA")),
      status = ArrivalStatus("UNK"),
      estDt = "2017-01-01T20:00:00Z",
      feedSources = Set(LiveFeedSource)
    ),
    Set(Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 2, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 3, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 5, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 4, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 7, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 6, None, None)
      ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)),
      Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 8, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 9, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 10, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 12, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 11, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 14, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 13, None, None)
        ), SplitRatiosNs.SplitSources.Historical, None))
  )

  val flightWithAllTypesOfAPISplitAndNoLiveNos: ApiFlightWithSplits = ApiFlightWithSplits(
    arrival(
      iata = "SA324",
      icao = "SA0324",
      schDt = "2017-01-01T20:00:00Z",
      actPax = None,
      apiPax = Option(100),
      maxPax = Option(100),
      terminal = T1,
      origin = PortCode("JHB"),
      operator = Option(Operator("SA")),
      status = ArrivalStatus("UNK"),
      estDt = "2017-01-01T20:00:00Z",
      feedSources = Set(LiveFeedSource)
    ),
    Set(Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 2, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 3, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 5, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 4, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 7, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 6, None, None)
      ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)),
      Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 8, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 9, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 10, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 12, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 11, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 14, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 13, None, None)
        ), SplitRatiosNs.SplitSources.Historical, None))
  )

  val flightWithoutFastTrackApiSplits: ApiFlightWithSplits = ApiFlightWithSplits(
    arrival(
      iata = "SA325",
      icao = "SA0325",
      schDt = "2017-01-01T20:00:00Z",
      actPax = Option(100),
      maxPax = Option(100),
      terminal = T1,
      origin = PortCode("JHC"),
      operator = Option(Operator("SA")),
      status = ArrivalStatus("UNK"),
      estDt = "2017-01-01T20:00:00Z",
      feedSources = Set(LiveFeedSource)
    ),
    Set(Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 3, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 3, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 3, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 1, None, None)
      ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)))
  )

  val flights: Seq[ApiFlightWithSplits] = List(
    flightWithAllTypesOfAPISplit,
    flightWithoutFastTrackApiSplits,
    ApiFlightWithSplits(
      arrival(
        iata = "SA326",
        icao = "SA0326",
        schDt = "2017-01-01T20:00:00Z",
        actPax = Option(100),
        maxPax = Option(100),
        terminal = T1,
        origin = PortCode("JHD"),
        operator = Option(Operator("SA")),
        status = ArrivalStatus("UNK"),
        estDt = "2017-01-01T20:00:00Z"),
      Set(Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 30, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 30, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 30, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 10, None, None)
        ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)))
    )
  )

  val flightsIncludingOneWithNoPaxNos = List(
    flightWithAllTypesOfAPISplitAndNoLiveNos,
    flightWithoutFastTrackApiSplits,
    ApiFlightWithSplits(
      arrival(
        iata = "SA326",
        icao = "SA0326",
        schDt = "2017-01-01T20:00:00Z",
        actPax = Option(100),
        maxPax = Option(100),
        terminal = T1,
        origin = PortCode("JHD"),
        operator = Option(Operator("SA")),
        status = ArrivalStatus("UNK"),
        estDt = "2017-01-01T20:00:00Z",
        feedSources = Set(LiveFeedSource)
      ),
      Set(Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 30, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 30, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 30, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 10, None, None)
        ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)))
    )
  )

  val codeShareFlights = List(
    flightWithAllTypesOfAPISplit,
    flightWithoutFastTrackApiSplits,
    ApiFlightWithSplits(
      arrival(
        iata = "SA326",
        icao = "SA0326",
        schDt = "2017-01-01T20:00:00Z",
        actPax = Option(105),
        maxPax = Option(105),
        terminal = T1,
        origin = PortCode("JHB"),
        operator = Option(Operator("SA")),
        status = ArrivalStatus("UNK"),
        estDt = "2017-01-01T20:00:00Z",
        feedSources = Set(LiveFeedSource)
      ),
      Set(Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 30, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 30, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 30, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 10, None, None)
        ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)))
    )
  )

  private val flightHeadings = """IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax"""
  private val apiHeadings = """Invalid API,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track"""

  private val withoutActualApiExport: FlightsWithSplitsWithoutActualApiExport = FlightsWithSplitsWithoutActualApiExportImpl(SDate("2017-01-01"), SDate("2017-01-01"), T1)
  private val withActualApiExport: FlightsWithSplitsWithActualApiExport = FlightsWithSplitsWithActualApiExportImpl(SDate("2017-01-01"), SDate("2017-01-01"), T1)
  private val cedatFlightExport = CedatFlightsExport(SDate("2017-01-01"), SDate("2017-01-01"), T1)

  "Given a list of arrivals with splits we should get back a CSV of arrival data using live feed numbers when available" >> {

    val resultStream = withoutActualApiExport
      .csvStream(Source(List(FlightsWithSplits(flights))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1 second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings
          |SA0324,SA0324,JHB,/,Expected,2017-01-01,20:00,20:00,,,,20:00,98,98,Y,7,15,32,44,11,23,29,35,,,,
          |SA0325,SA0325,JHC,/,Expected,2017-01-01,20:00,20:00,,,,20:00,100,100,Y,30,60,10,,,,,,,,,
          |SA0326,SA0326,JHD,/,Expected,2017-01-01,20:00,20:00,,,,20:00,100,100,,30,60,10,,,,,,,,,
          |""".stripMargin

    result === expected
  }

  "Given a list of arrivals with splits we should get back a CSV of arrival sorted by PCP time" >> {

    val flightsWithPcpTimes: Seq[ApiFlightWithSplits] = List(
      ApiFlightWithSplits(
        arrival(iata = "SA326", schDt = "2017-01-01T20:00:00Z", terminal = T1, origin = PortCode("JHD"), pcpDt = "2017-01-01T20:00:00Z"),
        Set()
      ),
      ApiFlightWithSplits(
        arrival(iata = "SA328", schDt = "2017-01-01T22:00:00Z", terminal = T1, origin = PortCode("JHD"), pcpDt = "2017-01-01T22:00:00Z"),
        Set()
      ),
      ApiFlightWithSplits(
        arrival(iata = "SA327", schDt = "2017-01-01T21:00:00Z", terminal = T1, origin = PortCode("JHD"), pcpDt = "2017-01-01T21:00:00Z"),
        Set()
      )
    )

    val resultStream = withoutActualApiExport
      .csvStream(Source(List(FlightsWithSplits(flightsWithPcpTimes))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1 second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings
          |SA0326,SA0326,JHD,/,Scheduled,2017-01-01,20:00,,,,,20:00,,0,,,,,,,,,,,,,
          |SA0327,SA0327,JHD,/,Scheduled,2017-01-01,21:00,,,,,21:00,,0,,,,,,,,,,,,,
          |SA0328,SA0328,JHD,/,Scheduled,2017-01-01,22:00,,,,,22:00,,0,,,,,,,,,,,,,
          |""".stripMargin

    result === expected
  }

  "Given a list of arrivals with splits we should get back a CSV of arrival data with unique entry for code Share Arrival flight" >> {

    val resultStream = withoutActualApiExport
      .csvStream(Source(List(FlightsWithSplits(codeShareFlights))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1 second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings
          |SA0325,SA0325,JHC,/,Expected,2017-01-01,20:00,20:00,,,,20:00,100,100,Y,30,60,10,,,,,,,,,
          |SA0326,SA0326,JHB,/,Expected,2017-01-01,20:00,20:00,,,,20:00,105,105,Y,32,62,11,,,,,,,,,
          |""".stripMargin

    result === expected
  }

  "Given a list of arrivals with splits and with live passenger numbers, we should use live passenger PCP numbers" >> {

    val resultStream = withoutActualApiExport
      .csvStream(Source(List(FlightsWithSplits(List(flightWithAllTypesOfAPISplit)))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1 second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings
          |SA0324,SA0324,JHB,/,Expected,2017-01-01,20:00,20:00,,,,20:00,98,98,Y,7,15,32,44,11,23,29,35,,,,
          |""".stripMargin

    result === expected
  }

  "When asking for Actual API Split Data" >> {

    "Given a list of Flights With Splits then I should get Api Split data for each flight" >> {
      val exporter = withActualApiExport

      val result = flights.map { flight =>
        exporter.actualAPISplitsForFlightInHeadingOrder(flight, exporter.actualApiHeadings)
      }

      val expected = List(
        List(0.0, 0.0, 0.0, 1.0, 2.0, 3.0, 0.0, 6.0, 7.0, 4.0, 5.0, 0.0),
        List(0.0, 0.0, 0.0, 3.0, 3.0, 3.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0),
        List(0.0, 0.0, 0.0, 30.0, 30.0, 30.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0))

      result === expected
    }
  }

  private val actualApiHeadings = """API Actual - B5J+ National to EEA,API Actual - B5J+ National to e-Gates,API Actual - B5J+ Child to EEA,API Actual - EEA Machine Readable to EEA,API Actual - EEA Machine Readable to e-Gates,API Actual - EEA Non-Machine Readable to EEA,API Actual - EEA Child to EEA,API Actual - Non-Visa National to Fast Track,API Actual - Visa National to Fast Track,API Actual - Non-Visa National to Non-EEA,API Actual - Visa National to Non-EEA,API Actual - Transit to Tx"""

  "Given a list of Flights With Splits then I should get all the data for with API numbers when live numbers are missing" >> {
    val resultStream = withActualApiExport
      .csvStream(Source(List(FlightsWithSplits(flightsIncludingOneWithNoPaxNos))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1 second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings,$actualApiHeadings
          |SA0324,SA0324,JHB,/,Expected,2017-01-01,20:00,20:00,,,,20:00,28,28,,2,4,9,13,3,7,8,10,,,,,0.0,0.0,0.0,1.0,2.0,3.0,0.0,6.0,7.0,4.0,5.0,0.0
          |SA0325,SA0325,JHC,/,Expected,2017-01-01,20:00,20:00,,,,20:00,100,100,Y,30,60,10,,,,,,,,,,0.0,0.0,0.0,3.0,3.0,3.0,0.0,0.0,0.0,1.0,0.0,0.0
          |SA0326,SA0326,JHD,/,Expected,2017-01-01,20:00,20:00,,,,20:00,100,100,,30,60,10,,,,,,,,,,0.0,0.0,0.0,30.0,30.0,30.0,0.0,0.0,0.0,10.0,0.0,0.0
          |""".stripMargin

    result === expected
  }

  "Given a list of Flights With Splits then I should get all the data with unique entry for code Share Arrival flight including API numbers" >> {
    val resultStream = withActualApiExport
      .csvStream(Source(List(FlightsWithSplits(codeShareFlights))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1 second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings,$actualApiHeadings
          |SA0325,SA0325,JHC,/,Expected,2017-01-01,20:00,20:00,,,,20:00,100,100,Y,30,60,10,,,,,,,,,,0.0,0.0,0.0,3.0,3.0,3.0,0.0,0.0,0.0,1.0,0.0,0.0
          |SA0326,SA0326,JHB,/,Expected,2017-01-01,20:00,20:00,,,,20:00,105,105,Y,32,62,11,,,,,,,,,,0.0,0.0,0.0,30.0,30.0,30.0,0.0,0.0,0.0,10.0,0.0,0.0
          |""".stripMargin

    result === expected
  }

  "Given a source of flights containing empty days and days with flights, then I should still get a CSV result" >> {
    val resultStream = withActualApiExport
      .csvStream(Source(List(
        FlightsWithSplits.empty,
        FlightsWithSplits.empty,
        FlightsWithSplits(codeShareFlights))
      ))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1 second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings,$actualApiHeadings
          |SA0325,SA0325,JHC,/,Expected,2017-01-01,20:00,20:00,,,,20:00,100,100,Y,30,60,10,,,,,,,,,,0.0,0.0,0.0,3.0,3.0,3.0,0.0,0.0,0.0,1.0,0.0,0.0
          |SA0326,SA0326,JHB,/,Expected,2017-01-01,20:00,20:00,,,,20:00,105,105,Y,32,62,11,,,,,,,,,,0.0,0.0,0.0,30.0,30.0,30.0,0.0,0.0,0.0,10.0,0.0,0.0
          |""".stripMargin

    result === expected
  }

  "Concerning CEDAT" >> {
    val cedatFlightHeadings = """IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax"""
    val cedatApiHeadings = """API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track"""
    val cedatActualApiHeadings = """API Actual - B5JSSK to Desk,API Actual - B5JSSK to eGates,API Actual - EEA (Machine Readable),API Actual - EEA (Non Machine Readable),API Actual - Fast Track (Non Visa),API Actual - Fast Track (Visa),API Actual - Non EEA (Non Visa),API Actual - Non EEA (Visa),API Actual - Transfer,API Actual - eGates"""

    "Given a source of flights containing empty days and days with flights, then I should still get a CSV result" >> {
      val resultStream = cedatFlightExport
        .csvStream(Source(List(
          FlightsWithSplits.empty,
          FlightsWithSplits.empty,
          FlightsWithSplits(codeShareFlights))
        ))

      val result: String = Await.result(resultStream.runWith(Sink.seq), 1 second).mkString

      val expected =
        s"""|$cedatFlightHeadings,$cedatApiHeadings,$cedatActualApiHeadings
            |SA0325,SA0325,JHC,/,Expected,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,,0.0,0.0,3.0,3.0,0.0,0.0,1.0,0.0,0.0,3.0
            |SA0326,SA0326,JHB,/,Expected,2017-01-01,20:00,20:00,,,,20:00,105,105,32,62,11,,,,,,,,,,0.0,0.0,30.0,30.0,0.0,0.0,10.0,0.0,0.0,30.0
            |""".stripMargin

      result === expected
    }
  }

  "Given a flight with API pax count within the 5% threshold of the feed pax count, and no live feed, then the 'Invalid API' column should be blank" >> {
    invalidApiFieldValue(actPax = 100, apiPax = 98, feedSources = Set(AclFeedSource)) === ""
  }

  "Given a flight with API pax count within the 5% threshold of the feed pax count, with a live feed, then the 'Invalid API' column should be blank" >> {
    invalidApiFieldValue(actPax = 100, apiPax = 98, feedSources = Set(LiveFeedSource, AclFeedSource)) === ""
  }

  "Given a flight with API pax count outside the 5% threshold of the feed pax count, but with no live feed, then the 'Invalid API' column should be blank" >> {
    invalidApiFieldValue(actPax = 100, apiPax = 75, feedSources = Set(AclFeedSource)) === ""
  }

  "Given a flight with API pax count outside the 5% threshold of the feed pax count, with a live feed, then the 'Invalid API' column should be 'Y'" >> {
    invalidApiFieldValue(actPax = 100, apiPax = 75, feedSources = Set(LiveFeedSource, AclFeedSource)) === "Y"
  }

  private def invalidApiFieldValue(actPax: Int, apiPax: Int, feedSources: Set[FeedSource]): String = {
    val arrival = ArrivalGenerator.arrival(actPax = Option(actPax), feedSources = feedSources)
    val splits = Splits(Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, apiPax, None, None)),
      SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))
    val fws = ApiFlightWithSplits(arrival, Set(splits))
    val eventualResult = withActualApiExport.csvStream(Source(List(FlightsWithSplits(Iterable(fws))))).runWith(Sink.seq)
    val result = Await.result(eventualResult, 1 second)
    val columnIndexOfInvalidApi = result.head.split(",").indexOf("Invalid API")
    val invalidApiFieldValue = result.drop(1).head.split(",")(columnIndexOfInvalidApi)
    invalidApiFieldValue
  }
}
