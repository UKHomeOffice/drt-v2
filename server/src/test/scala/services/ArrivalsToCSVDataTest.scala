package services

import drt.shared.Terminals.T1
import drt.shared._
import org.specs2.mutable.Specification
import services.exports.Exports
import services.exports.summaries.flights.{TerminalFlightsSummary, TerminalFlightsWithActualApiSummary}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class ArrivalsToCSVDataTest extends Specification {

  import CSVData._

  import controllers.ArrivalGenerator.arrival

  private val flightWithAllTypesOfAPISplit = ApiFlightWithSplits(
    arrival(iata = "SA324", icao = "SA0324", schDt = "2017-01-01T20:00:00Z", actPax = Option(100), maxPax = Option(100), terminal = T1, origin = PortCode("JHB"), operator = Option(Operator("SA")), status = ArrivalStatus("UNK"), estDt = "2017-01-01T20:00:00Z"),
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
  val flightWithoutFastTrackApiSplits = ApiFlightWithSplits(
    arrival(iata = "SA325", icao = "SA0325", schDt = "2017-01-01T20:00:00Z", actPax = Option(100), maxPax = Option(100), terminal = T1, origin = PortCode("JHB"), operator = Option(Operator("SA")), status = ArrivalStatus("UNK"), estDt = "2017-01-01T20:00:00Z"),
    Set(Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 3, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 3, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 3, None),
        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 1, None)
        ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)))
    )
  val flights = List(
    flightWithAllTypesOfAPISplit,
    flightWithoutFastTrackApiSplits,
    ApiFlightWithSplits(
      arrival(iata = "SA326", icao = "SA0326", schDt = "2017-01-01T20:00:00Z", actPax = Option(100), maxPax = Option(100), terminal = T1, origin = PortCode("JHB"), operator = Option(Operator("SA")), status = ArrivalStatus("UNK"), estDt = "2017-01-01T20:00:00Z"),
      Set(Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 10, None)
          ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)))
      )
    )

  "Given a list of arrivals with splits we should get back a CSV of arrival data" >> {

    val result = TerminalFlightsSummary(flights, Exports.millisToLocalIsoDateOnly, Exports.millisToLocalHoursAndMinutes).toCsvWithHeader

    val expected =
      """ |IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track
        |SA0324,SA0324,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,7,15,32,46,12,23,30,35,,,,
        |SA0325,SA0325,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,
        |SA0326,SA0326,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,""".stripMargin

    result === expected
  }
  "Given a list of arrivals when getting csv without headings, we should get the list without headings" >> {

    val result = TerminalFlightsSummary(flights, Exports.millisToLocalIsoDateOnly, Exports.millisToLocalHoursAndMinutes).toCsv

    val expected =
      """ |SA0324,SA0324,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,7,15,32,46,12,23,30,35,,,,
        |SA0325,SA0325,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,
        |SA0326,SA0326,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,""".stripMargin

    result === expected
  }

  "When asking for Actual API Split Data" >> {
    "Given a list of Flights With Splits then I should get a list of headings for each of the API Splits" >> {
      val headings = Exports.actualApiHeadings(List(flightWithoutFastTrackApiSplits, flightWithAllTypesOfAPISplit))

      val expected = List(
        "API Actual - EEA (Machine Readable)",
        "API Actual - EEA (Non Machine Readable)",
        "API Actual - Fast Track (Non Visa)",
        "API Actual - Fast Track (Visa)",
        "API Actual - Non EEA (Non Visa)",
        "API Actual - Non EEA (Visa)",
        "API Actual - eGates"
        )

      headings === expected
    }

    "Given a list of Flights With Splits then I should get Api Split data for each flight" >> {
      val result = flights.map { flight =>
        Exports.actualAPISplitsForFlightInHeadingOrder(flight, Exports.actualApiHeadings(flights))
      }

      val expected = List(
        List(1.0, 3.0, 6.0, 7.0, 4.0, 5.0, 2.0),
        List(3.0, 3.0, 0, 0, 1.0, 0, 3.0),
        List(30.0, 30.0, 0, 0, 10.0, 0, 30.0)
        )

      result === expected
    }

    "Given a list of Flights With Splits then I should get all the data for each flight including API numbers" >> {
      val result = TerminalFlightsWithActualApiSummary(flights, Exports.millisToLocalIsoDateOnly, Exports.millisToLocalHoursAndMinutes).toCsvWithHeader

      val actualAPIHeadings = "API Actual - EEA (Machine Readable),API Actual - EEA (Non Machine Readable)," +
        "API Actual - Fast Track (Non Visa),API Actual - Fast Track (Visa),API Actual - Non EEA (Non Visa)," +
        "API Actual - Non EEA (Visa),API Actual - eGates"
      val expected =
        s"""|IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track,$actualAPIHeadings
            |SA0324,SA0324,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,7,15,32,46,12,23,30,35,,,,,1.0,3.0,6.0,7.0,4.0,5.0,2.0
            |SA0325,SA0325,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,,3.0,3.0,0.0,0.0,1.0,0.0,3.0
            |SA0326,SA0326,JHB,/,UNK,2017-01-01,20:00,20:00,,,,20:00,100,100,30,60,10,,,,,,,,,,30.0,30.0,0.0,0.0,10.0,0.0,30.0""".stripMargin

      result === expected
    }
  }
}
