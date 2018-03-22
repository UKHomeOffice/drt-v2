package services

import drt.shared._
import org.specs2.mutable.Specification

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global


class ArrivalsToCSVDataTest extends Specification {

  import CSVData._
  import controllers.ArrivalGenerator.apiFlight

  val flights = List(
    ApiFlightWithSplits(
      apiFlight(1, "SA324", "SA0324", "2017-01-01T20:00:00Z", 100, 100, None, "T1", "JHB", "SA", "UNK", "2017-01-01T20:00:00Z"),
      Set(ApiSplits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 2, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 3, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 4, None),
          ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 5, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 6, None),
          ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 7, None)
        ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DqEventCodes.DepartureConfirmed)),
        ApiSplits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 8, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 9, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 10, None),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 11, None),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 12, None),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 13, None),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 14, None)
          ), SplitRatiosNs.SplitSources.Historical, None))
    ),
    ApiFlightWithSplits(
      apiFlight(2, "SA325", "SA0325", "2017-01-01T20:00:00Z", 100, 100, None, "T1", "JHB", "SA", "UNK", "2017-01-01T20:00:00Z"),
      Set(ApiSplits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 10, None)
        ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DqEventCodes.DepartureConfirmed)))
    ),
    ApiFlightWithSplits(
      apiFlight(3, "SA326", "SA0326", "2017-01-01T20:00:00Z", 100, 100, None, "T1", "JHB", "SA", "UNK", "2017-01-01T20:00:00Z"),
      Set(ApiSplits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 30, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 10, None)
        ), SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DqEventCodes.DepartureConfirmed)))
    )
  )

  "Given a list of arrivals with splits we should get back a CSV of arrival data" >> {

    val result = flightsWithSplitsToCSV(flights)

    val expected =
      """ |IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Arrival,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track
          |SA0324,SA0324,JHB,/,UNK,2017-01-01T20:00:00Z,2017-01-01T20:00:00Z,,,,2017-01-01T20:00:00Z,100,100,7,15,32,46,12,23,30,35,,,,
          |SA0325,SA0325,JHB,/,UNK,2017-01-01T20:00:00Z,2017-01-01T20:00:00Z,,,,2017-01-01T20:00:00Z,100,100,30,60,10,,,,,,,,,
          |SA0326,SA0326,JHB,/,UNK,2017-01-01T20:00:00Z,2017-01-01T20:00:00Z,,,,2017-01-01T20:00:00Z,100,100,30,60,10,,,,,,,,,""".stripMargin

    result === expected
  }
  "Given a list of arrivals when addHeaders is false, we should get the list without headings" >> {

    val result = flightsWithSplitsToCSV(flights, false)

    val expected =
      """ |SA0324,SA0324,JHB,/,UNK,2017-01-01T20:00:00Z,2017-01-01T20:00:00Z,,,,2017-01-01T20:00:00Z,100,100,7,15,32,46,12,23,30,35,,,,
          |SA0325,SA0325,JHB,/,UNK,2017-01-01T20:00:00Z,2017-01-01T20:00:00Z,,,,2017-01-01T20:00:00Z,100,100,30,60,10,,,,,,,,,
          |SA0326,SA0326,JHB,/,UNK,2017-01-01T20:00:00Z,2017-01-01T20:00:00Z,,,,2017-01-01T20:00:00Z,100,100,30,60,10,,,,,,,,,""".stripMargin

    result === expected
  }

  "When exporting multiple days to CSV" >> {
    "Given a list of multiple Future potential day CSV exports then I should get a single future CSV with all days in it" >> {
      val futureDays: Seq[Future[Option[String]]] = List(
        Future(Option("Day 1, csv, export")),
        Future(Option("Day 2, csv, export")),
        Future(Option("Day 3, csv, export"))
      )

      val expected =
        """|Day 1, csv, export
           |Day 2, csv, export
           |Day 3, csv, export""".stripMargin

      val resultFuture = CSVData.multiDayToSingleExport(futureDays)
      val result = Await.result(resultFuture, 5 seconds)

      result === expected
    }
  }
}
