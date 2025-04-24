package services.exports

import org.apache.pekko.stream.scaladsl.{Sink, Source}
import controllers.ArrivalGenerator
import controllers.ArrivalGenerator.live
import passengersplits.parsing.VoyageManifestParser._
import services.crunch.CrunchTestLike
import services.exports.flights.templates.{FlightsWithSplitsWithActualApiExport, FlightsWithSplitsWithActualApiExportImpl, FlightsWithSplitsWithoutActualApiExport, FlightsWithSplitsWithoutActualApiExportImpl}
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.EventTypes.DC
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.concurrent.Await
import scala.concurrent.duration._

class StreamingFlightsExportSpec extends CrunchTestLike {
  val flightWithAllTypesOfAPISplit: ApiFlightWithSplits = ApiFlightWithSplits(
    live(
      iata = "SA324",
      schDt = "2017-01-01T20:00:00Z",
      maxPax = Option(100),
      terminal = T1,
      origin = PortCode("JHB"),
      operator = Option(Operator("SA")),
      status = ArrivalStatus("UNK"),
      estDt = "2017-01-01T20:00:00Z"
    ).toArrival(LiveFeedSource).copy(
      FeedSources = Set(LiveFeedSource),
      PassengerSources = Map(
        LiveFeedSource -> Passengers(Option(98), None),
        ApiFeedSource -> Passengers(Option(100), None),
      )
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
    live(
      iata = "SA324",
      schDt = "2017-01-01T20:00:00Z",
      maxPax = Option(100),
      terminal = T1,
      origin = PortCode("JHB"),
      operator = Option(Operator("SA")),
      status = ArrivalStatus("UNK"),
      estDt = "2017-01-01T20:00:00Z"
    ).toArrival(LiveFeedSource).copy(
      FeedSources = Set(ApiFeedSource),
      PassengerSources = Map(ApiFeedSource -> Passengers(Option(28), None))
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
    live(
      iata = "SA325",
      schDt = "2017-01-01T20:00:00Z",
      maxPax = Option(100),
      terminal = T1,
      origin = PortCode("JHC"),
      operator = Option(Operator("SA")),
      status = ArrivalStatus("UNK"),
      estDt = "2017-01-01T20:00:00Z"
    ).toArrival(LiveFeedSource).copy(
      FeedSources = Set(LiveFeedSource),
      PassengerSources = Map(
        LiveFeedSource -> Passengers(Option(100), None),
        ApiFeedSource -> Passengers(Option(100), None),
      )
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
      live(
        iata = "SA326",
        schDt = "2017-01-01T20:00:00Z",
        maxPax = Option(100),
        terminal = T1,
        origin = PortCode("JHD"),
        operator = Option(Operator("SA")),
        status = ArrivalStatus("UNK"),
        estDt = "2017-01-01T20:00:00Z"
      ).toArrival(LiveFeedSource).copy(
        PassengerSources = Map(AclFeedSource -> Passengers(Option(100), None))
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

  val flightsIncludingOneWithNoPaxNos: Seq[ApiFlightWithSplits] = List(
    flightWithAllTypesOfAPISplitAndNoLiveNos,
    flightWithoutFastTrackApiSplits,
    ApiFlightWithSplits(
      live(
        iata = "SA326",
        schDt = "2017-01-01T20:00:00Z",
        maxPax = Option(100),
        terminal = T1,
        origin = PortCode("JHD"),
        operator = Option(Operator("SA")),
        status = ArrivalStatus("UNK"),
        estDt = "2017-01-01T20:00:00Z"
      ).toArrival(LiveFeedSource).copy(
        FeedSources = Set(LiveFeedSource),
        PassengerSources = Map(LiveFeedSource -> Passengers(Option(100), None))
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

  val codeShareFlights: Seq[ApiFlightWithSplits] = List(
    flightWithAllTypesOfAPISplit,
    flightWithoutFastTrackApiSplits,
    ApiFlightWithSplits(
      live(
        iata = "SA326",
        schDt = "2017-01-01T20:00:00Z",
        maxPax = Option(105),
        terminal = T1,
        origin = PortCode("JHB"),
        operator = Option(Operator("SA")),
        status = ArrivalStatus("UNK"),
        estDt = "2017-01-01T20:00:00Z"
      ).toArrival(LiveFeedSource).copy(
        FeedSources = Set(LiveFeedSource),
        PassengerSources = Map(
          LiveFeedSource -> Passengers(Option(105), None),
          ApiFeedSource -> Passengers(Option(105), None),
        )
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

  private val flightHeadings =
    """IATA,Terminal,Origin,Gate/Stand,Status,Scheduled,Predicted Arrival,Est Arrival,Act Arrival,Est Chocks,Act Chocks,Minutes off scheduled,Est PCP,Capacity,Total Pax,PCP Pax"""
  private val apiHeadings =
    """Invalid API,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track"""

  private val withoutActualApiExport: FlightsWithSplitsWithoutActualApiExport =
    FlightsWithSplitsWithoutActualApiExportImpl(LocalDate(2017, 1, 1), LocalDate(2017, 1, 1), Seq(T1), paxFeedSourceOrder)
  private val withActualApiExport: FlightsWithSplitsWithActualApiExport =
    FlightsWithSplitsWithActualApiExportImpl(LocalDate(2017, 1, 1), LocalDate(2017, 1, 1), Seq(T1), paxFeedSourceOrder)

  "Given a list of arrivals with splits we should get back a CSV of arrival data using live feed numbers when available" >> {

    val resultStream = withoutActualApiExport
      .csvStream(Source(List((flights, VoyageManifests.empty))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1.second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings
          |SA0324,T1,JHB,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,100,98,98,Y,7,15,32,44,11,23,29,35,,,,
          |SA0325,T1,JHC,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,100,100,100,Y,30,60,10,,,,,,,,,
          |SA0326,T1,JHD,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,100,100,100,Y,30,60,10,,,,,,,,,
          |""".stripMargin

    result === expected
  }

  "Given a list of arrivals with splits we should get back a CSV of arrival sorted by PCP time" >> {

    val flightsWithPcpTimes: Seq[ApiFlightWithSplits] = List(
      ApiFlightWithSplits(
        live(iata = "SA326", schDt = "2017-01-01T20:00:00Z", terminal = T1, origin = PortCode("JHD")).toArrival(LiveFeedSource)
          .copy(PcpTime = Option(SDate("2017-01-01T20:00:00Z").millisSinceEpoch)),
        Set()
      ),
      ApiFlightWithSplits(
        live(iata = "SA328", schDt = "2017-01-01T22:00:00Z", terminal = T1, origin = PortCode("JHD")).toArrival(LiveFeedSource)
          .copy(PcpTime = Option(SDate("2017-01-01T22:00:00Z").millisSinceEpoch)),
        Set()
      ),
      ApiFlightWithSplits(
        live(iata = "SA327", schDt = "2017-01-01T21:00:00Z", terminal = T1, origin = PortCode("JHD")).toArrival(LiveFeedSource)
          .copy(PcpTime = Option(SDate("2017-01-01T21:00:00Z").millisSinceEpoch)),
        Set()
      )
    )

    val resultStream = withoutActualApiExport
      .csvStream(Source(List((flightsWithPcpTimes, VoyageManifests.empty))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1.second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings
          |SA0326,T1,JHD,/,Scheduled,2017-01-01 20:00,,,,,,,2017-01-01 20:00,n/a,,,,,,,,,,,,,,,
          |SA0327,T1,JHD,/,Scheduled,2017-01-01 21:00,,,,,,,2017-01-01 21:00,n/a,,,,,,,,,,,,,,,
          |SA0328,T1,JHD,/,Scheduled,2017-01-01 22:00,,,,,,,2017-01-01 22:00,n/a,,,,,,,,,,,,,,,
          |""".stripMargin

    result === expected
  }

  "Given a list of arrivals with splits we should get back a CSV of arrival data with unique entry for code Share Arrival flight" >> {

    val resultStream = withoutActualApiExport
      .csvStream(Source(List((codeShareFlights, VoyageManifests.empty))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1.second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings
          |SA0325,T1,JHC,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,100,100,100,Y,30,60,10,,,,,,,,,
          |SA0326,T1,JHB,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,105,105,105,Y,32,62,11,,,,,,,,,
          |""".stripMargin

    result === expected
  }

  "Given a list of arrivals with splits and with live passenger numbers, we should use live passenger PCP numbers" >> {
    val resultStream = withoutActualApiExport
      .csvStream(Source(List((List(flightWithAllTypesOfAPISplit), VoyageManifests.empty))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1.second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings
          |SA0324,T1,JHB,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,100,98,98,Y,7,15,32,44,11,23,29,35,,,,
          |""".stripMargin

    result === expected
  }

  "When asking for Actual API Split Data" >> {

    "Given a list of Flights With Splits then I should get Api Split data for each flight" >> {

      val result = flights.map { flight =>
        FlightExports.actualAPISplitsForFlightInHeadingOrder(flight, ArrivalExportHeadings.actualApiHeadings.split(","))
      }

      val expected = List(
        List(2.0, 1.0, 3.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 5.0, 4.0, 7.0, 6.0),
        List(3.0, 3.0, 3.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0),
        List(30.0, 30.0, 30.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0))

      result === expected
    }
  }

  private val actualApiHeadings = """API Actual - EEA Machine Readable to e-Gates,API Actual - EEA Machine Readable to EEA,API Actual - EEA Non-Machine Readable to EEA,API Actual - EEA Child to EEA,API Actual - GBR National to e-Gates,API Actual - GBR National to EEA,API Actual - GBR National Child to EEA,API Actual - B5J+ National to e-Gates,API Actual - B5J+ National to EEA,API Actual - B5J+ Child to EEA,API Actual - Visa National to Non-EEA,API Actual - Non-Visa National to Non-EEA,API Actual - Visa National to Fast Track,API Actual - Non-Visa National to Fast Track,Nationalities,Ages"""

  "Given a list of Flights With Splits then I should get all the data for with API numbers when live numbers are missing" >> {
    val resultStream = withActualApiExport
      .csvStream(Source(List((flightsIncludingOneWithNoPaxNos, VoyageManifests.empty))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1.second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings,$actualApiHeadings
          |SA0324,T1,JHB,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,100,28,28,,2,4,9,13,3,7,8,10,,,,,2.0,1.0,3.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,5.0,4.0,7.0,6.0,"",""
          |SA0325,T1,JHC,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,100,100,100,Y,30,60,10,,,,,,,,,,3.0,3.0,3.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,"",""
          |SA0326,T1,JHD,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,100,100,100,,30,60,10,,,,,,,,,,30.0,30.0,30.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,10.0,0.0,0.0,"",""
          |""".stripMargin

    result === expected
  }

  "Given a Flight With Splits and a VoyageManifests with a matching arrival " +
    "I should get all the data with API nos plus the nationalities breakdown in size then alphabetical order, and age breakdowns in ascending range order" >> {
    val manifests = VoyageManifests(Set(
      VoyageManifest(DC, PortCode("AAA"), flightWithAllTypesOfAPISplit.apiFlight.Origin, flightWithAllTypesOfAPISplit.apiFlight.VoyageNumber,
        flightWithAllTypesOfAPISplit.apiFlight.CarrierCode, ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("20:00"), List(
          PassengerInfoJson(None, Nationality("XXX"), EeaFlag("Y"), Option(PaxAge(50)), None, InTransit(false), None, Option(Nationality("GBR")), None),
          PassengerInfoJson(None, Nationality("XXX"), EeaFlag("Y"), Option(PaxAge(25)), None, InTransit(false), None, Option(Nationality("USA")), None),
          PassengerInfoJson(None, Nationality("XXX"), EeaFlag("Y"), Option(PaxAge(5)), None, InTransit(false), None, Option(Nationality("FRA")), None),
          PassengerInfoJson(None, Nationality("XXX"), EeaFlag("Y"), Option(PaxAge(30)), None, InTransit(false), None, Option(Nationality("FRA")), None),
        ))))
    val resultStream = withActualApiExport
      .csvStream(Source(List((List(flightWithAllTypesOfAPISplit), manifests))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1.second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings,$actualApiHeadings
          |SA0324,T1,JHB,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,100,98,98,Y,7,15,32,44,11,23,29,35,,,,,2.0,1.0,3.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,5.0,4.0,7.0,6.0,"FRA:2,GBR:1,USA:1","0-11:1,25-49:2,50-65:1"
          |""".stripMargin

    result === expected
  }

  "Given a list of Flights With Splits then I should get all the data with unique entry for code Share Arrival flight including API numbers" >> {
    val resultStream = withActualApiExport
      .csvStream(Source(List((codeShareFlights, VoyageManifests.empty))))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1.second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings,$actualApiHeadings
          |SA0325,T1,JHC,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,100,100,100,Y,30,60,10,,,,,,,,,,3.0,3.0,3.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,"",""
          |SA0326,T1,JHB,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,105,105,105,Y,32,62,11,,,,,,,,,,30.0,30.0,30.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,10.0,0.0,0.0,"",""
          |""".stripMargin

    result === expected
  }

  "Given a source of flights containing empty days and days with flights, then I should still get a CSV result" >> {
    val resultStream = withActualApiExport
      .csvStream(Source(List(
        (Seq.empty, VoyageManifests.empty),
        (Seq.empty, VoyageManifests.empty),
        (codeShareFlights, VoyageManifests.empty))
      ))

    val result: String = Await.result(resultStream.runWith(Sink.seq), 1.second).mkString

    val expected =
      s"""|$flightHeadings,$apiHeadings,$actualApiHeadings
          |SA0325,T1,JHC,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,100,100,100,Y,30,60,10,,,,,,,,,,3.0,3.0,3.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,"",""
          |SA0326,T1,JHB,/,Expected,2017-01-01 20:00,,2017-01-01 20:00,,,,,,105,105,105,Y,32,62,11,,,,,,,,,,30.0,30.0,30.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,10.0,0.0,0.0,"",""
          |""".stripMargin

    result === expected
  }

  "Given a flight with API pax count, and no live feed, then the 'Invalid API' column should be blank" >> {
    invalidApiFieldValue(feedSources = Set(AclFeedSource), passengerSources = Map(
      AclFeedSource -> Passengers(Option(100), None),
      ApiFeedSource -> Passengers(Option(98), None))
    ) === ""
  }

  "Given a flight with API pax count within the 5% threshold of the feed pax count, with a live feed, then the 'Invalid API' column should be blank" >> {
    invalidApiFieldValue(feedSources = Set(LiveFeedSource, ApiFeedSource),
      passengerSources = Map(LiveFeedSource -> Passengers(Option(100), None),
        ApiFeedSource -> Passengers(Option(98), None))
    ) === ""
  }

  "Given a flight with API pax count outside the 5% threshold of the feed pax count, with a live feed, then the 'Invalid API' column should be 'Y'" >> {
    invalidApiFieldValue(feedSources = Set(LiveFeedSource, ApiFeedSource),
      passengerSources = Map(LiveFeedSource -> Passengers(Option(100), None),
        ApiFeedSource -> Passengers(Option(75), None))
    ) === "Y"
  }

  private def invalidApiFieldValue(feedSources: Set[FeedSource], passengerSources: Map[FeedSource, Passengers]): String = {
    val arrival = ArrivalGenerator.live().toArrival(LiveFeedSource).copy(FeedSources = feedSources, PassengerSources = passengerSources)
    val splits = Splits(Set(
      ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable,
        Queues.EGate,
        passengerSources.get(ApiFeedSource).flatMap(_.actual).getOrElse(0).toDouble,
        None,
        None)),
      SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))
    val fws = ApiFlightWithSplits(arrival, Set(splits))
    val eventualResult = withActualApiExport.csvStream(Source(List((Iterable(fws), VoyageManifests.empty)))).runWith(Sink.seq)
    val result = Await.result(eventualResult, 1.second)
    val columnIndexOfInvalidApi = result.head.split(",").indexOf("Invalid API")
    val invalidApiFieldValue = result.drop(1).head.split(",")(columnIndexOfInvalidApi)
    invalidApiFieldValue
  }
}
