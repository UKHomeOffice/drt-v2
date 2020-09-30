package services.`export`

import akka.stream.scaladsl.{Sink, Source}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.T1
import drt.shared._
import services.crunch.CrunchTestLike
import services.exports.StreamingFlightsExport

import scala.concurrent.Await
import scala.concurrent.duration._

class StreamingFlightsExportSpec extends CrunchTestLike {

  import controllers.ArrivalGenerator.arrival

  val flightWithAllTypesOfAPISplit = ApiFlightWithSplits(
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
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 2, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 3, None),
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 5, None),
        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 4, None),
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 7, None),
        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 6, None)
      ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)),
      Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 8, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 9, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 10, None),
          ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 12, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 11, None),
          ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 14, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 13, None)
        ), SplitRatiosNs.SplitSources.Historical, None))
  )
  val flightWithAllTypesOfAPISplitAndNoLiveNos = ApiFlightWithSplits(
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
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 2, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 3, None),
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 5, None),
        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 4, None),
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 7, None),
        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 6, None)
      ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)),
      Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 8, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 9, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 10, None),
          ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 12, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 11, None),
          ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 14, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 13, None)
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
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 3, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 3, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 3, None),
        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 1, None)
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
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 10, None)
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
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 10, None)
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
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 10, None)
        ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)))
    )
  )



  "Given a list of arrivals with splits we should get back a CSV of arrival data using live feed numbers when available" >> {

    val resultStream = StreamingFlightsExport(PcpPax.bestPaxEstimateWithApi)
      .toCSV(Source(List(FlightsWithSplits(flights))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1 second).mkString("\n")

    val expected =
      """|IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track
         |SA0325,SA0325,JHC,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,
         |SA0324,SA0324,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,98,98,7,15,32,44,11,23,29,35,,,,
         |SA0326,SA0326,JHD,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,
         |""".stripMargin

    result === expected
  }

  "Given a list of arrivals with splits we should get back a CSV of arrival data with unique entry for code Share Arrival flight" >> {

    val resultStream = StreamingFlightsExport(PcpPax.bestPaxEstimateWithApi)
      .toCSV(Source(List(FlightsWithSplits(codeShareFlights))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1 second).mkString("\n")

    val expected =
      """|IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track
         |SA0325,SA0325,JHC,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,
         |SA0326,SA0326,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,105,105,32,62,11,,,,,,,,,
         |""".stripMargin

    result === expected
  }
//
//
//  "Given a list of arrivals when getting csv without headings, we should get the list without headings" >> {
//
//    val result = TerminalFlightsSummary(
//      flights,
//      Exports.millisToLocalIsoDateOnly,
//      Exports.millisToLocalHoursAndMinutes,
//      PcpPax.bestPaxEstimateWithApi
//    ).toCsv
//
//    val expected =
//      """|SA0325,SA0325,JHC,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,
//         |SA0324,SA0324,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,98,98,7,15,32,44,11,23,29,35,,,,
//         |SA0326,SA0326,JHD,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,
//         |""".stripMargin
//
//    result === expected
//  }
//
//  "Given a list of arrivals when getting csv without headings, we should get with unique entry for code Share Arrival flight and the list without headings" >> {
//
//    val result = TerminalFlightsSummary(
//      codeShareFlights,
//      Exports.millisToLocalIsoDateOnly,
//      Exports.millisToLocalHoursAndMinutes,
//      PcpPax.bestPaxEstimateWithApi
//    ).toCsv
//
//    val expected =
//      """|SA0325,SA0325,JHC,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,
//         |SA0326,SA0326,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,105,105,32,62,11,,,,,,,,,
//         |""".stripMargin
//
//    result === expected
//  }
//
//  "Given a list of arrivals with splits and with live passenger numbers, we should use live passenger PCP numbers" >> {
//
//    val result = TerminalFlightsSummary(
//      List(flightWithAllTypesOfAPISplit),
//      Exports.millisToLocalIsoDateOnly,
//      Exports.millisToLocalHoursAndMinutes,
//      PcpPax.bestPaxEstimateWithApi
//    )
//      .toCsvWithHeader
//
//    val expected =
//      """|IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track
//         |SA0324,SA0324,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,98,98,7,15,32,44,11,23,29,35,,,,
//         |""".stripMargin
//
//    result === expected
//  }
//
//  import TerminalFlightsWithActualApiSummary._
//
//  "When asking for Actual API Split Data" >> {
//    "Given a list of Flights With Splits then I should get a list of headings for each of the API Splits" >> {
//      val headings = actualApiHeadingsForFlights(List(flightWithoutFastTrackApiSplits, flightWithAllTypesOfAPISplit))
//
//      val expected = List(
//        "API Actual - B5JSSK to Desk",
//        "API Actual - B5JSSK to eGates",
//        "API Actual - EEA (Machine Readable)",
//        "API Actual - EEA (Non Machine Readable)",
//        "API Actual - Fast Track (Non Visa)",
//        "API Actual - Fast Track (Visa)",
//        "API Actual - Non EEA (Non Visa)",
//        "API Actual - Non EEA (Visa)",
//        "API Actual - Transfer",
//        "API Actual - eGates"
//      )
//
//
//      headings === expected
//    }
//
//    "Given a list of Flights With Splits then I should get Api Split data for each flight" >> {
//      val result = flights.map { flight =>
//        actualAPISplitsForFlightInHeadingOrder(flight, actualApiHeadingsForFlights(flights))
//      }
//
//      val expected = List(
//        List(0.0, 0.0, 1.0, 3.0, 6.0, 7.0, 4.0, 5.0, 0.0, 2.0),
//        List(0.0, 0.0, 3.0, 3.0, 0.0, 0.0, 1.0, 0.0, 0.0, 3.0),
//        List(0.0, 0.0, 30.0, 30.0, 0.0, 0.0, 10.0, 0.0, 0.0, 30.0)
//      )
//
//      result === expected
//    }
//
//    "Given a list of Flights With Splits then I should get all the data for with API numbers when live numbers are missing" >> {
//      val result = TerminalFlightsWithActualApiSummary(
//        flightsIncludingOneWithNoPaxNos,
//        Exports.millisToLocalIsoDateOnly,
//        Exports.millisToLocalHoursAndMinutes,
//        PcpPax.bestPaxEstimateWithApi
//      ).toCsvWithHeader
//
//      val actualAPIHeadings = "API Actual - EEA (Machine Readable),API Actual - EEA (Non Machine Readable)," +
//        "API Actual - Fast Track (Non Visa),API Actual - Fast Track (Visa),API Actual - Non EEA (Non Visa)," +
//        "API Actual - Non EEA (Visa),API Actual - eGates"
//      val expected =
//        s"""|IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track,API Actual - B5JSSK to Desk,API Actual - B5JSSK to eGates,API Actual - EEA (Machine Readable),API Actual - EEA (Non Machine Readable),API Actual - Fast Track (Non Visa),API Actual - Fast Track (Visa),API Actual - Non EEA (Non Visa),API Actual - Non EEA (Visa),API Actual - Transfer,API Actual - eGates
//            |SA0325,SA0325,JHC,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,,0.0,0.0,3.0,3.0,0.0,0.0,1.0,0.0,0.0,3.0
//            |SA0324,SA0324,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,,100,7,15,32,46,12,23,30,35,,,,,0.0,0.0,1.0,3.0,6.0,7.0,4.0,5.0,0.0,2.0
//            |SA0326,SA0326,JHD,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,,0.0,0.0,30.0,30.0,0.0,0.0,10.0,0.0,0.0,30.0
//            |""".stripMargin
//
//      result === expected
//    }
//
//    "Given a list of Flights With Splits then I should get all the data with unique entry for code Share Arrival flight including API numbers" >> {
//      val result = TerminalFlightsWithActualApiSummary(
//        codeShareFlights,
//        Exports.millisToLocalIsoDateOnly,
//        Exports.millisToLocalHoursAndMinutes,
//        PcpPax.bestPaxEstimateWithApi
//      )
//        .toCsvWithHeader
//
//      val actualAPIHeadings = "API Actual - EEA (Machine Readable),API Actual - EEA (Non Machine Readable)," +
//        "API Actual - Fast Track (Non Visa),API Actual - Fast Track (Visa),API Actual - Non EEA (Non Visa)," +
//        "API Actual - Non EEA (Visa),API Actual - eGates"
//      val expected =
//        s"""|IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track,API Actual - B5JSSK to Desk,API Actual - B5JSSK to eGates,API Actual - EEA (Machine Readable),API Actual - EEA (Non Machine Readable),API Actual - Fast Track (Non Visa),API Actual - Fast Track (Visa),API Actual - Non EEA (Non Visa),API Actual - Non EEA (Visa),API Actual - Transfer,API Actual - eGates
//            |SA0325,SA0325,JHC,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,,0.0,0.0,3.0,3.0,0.0,0.0,1.0,0.0,0.0,3.0
//            |SA0326,SA0326,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,105,105,32,62,11,,,,,,,,,,0.0,0.0,30.0,30.0,0.0,0.0,10.0,0.0,0.0,30.0
//            |""".stripMargin
//
//      result === expected
//    }
//
//  }


}
