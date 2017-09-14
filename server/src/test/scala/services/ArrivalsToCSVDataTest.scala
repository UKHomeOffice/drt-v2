package services

import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import org.specs2.mutable.Specification


class ArrivalsToCSVDataTest extends Specification {

  import controllers.ArrivalGenerator.apiFlight
  import CSVData._

  "Given a list of arrivals with splits we should get back a CSV of arrival data" >> {

    val flights = List(
      ApiFlightWithSplits(
        apiFlight(1, "SA324", "SA0324", "2017-01-01T20:00:00Z", 100, 100, None, "T1", "JHB", "SA", "UNK", "2017-01-01T20:00:00Z"),
        List(ApiSplits(
          List(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 2),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 3),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 4),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 5),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 6),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 7)
          ), SplitRatiosNs.SplitSources.ApiSplitsWithCsvPercentage),
          ApiSplits(
            List(
              ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 8),
              ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 9),
              ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 10),
              ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 11),
              ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 12),
              ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 13),
              ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 14)
            ), SplitRatiosNs.SplitSources.Historical))
      ),
      ApiFlightWithSplits(
        apiFlight(2, "SA325", "SA0325", "2017-01-01T20:00:00Z", 100, 100, None, "T1", "JHB", "SA", "UNK", "2017-01-01T20:00:00Z"),
        List(ApiSplits(
          List(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 30),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 30),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 30),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 10)
          ), SplitRatiosNs.SplitSources.ApiSplitsWithCsvPercentage))
      ),
      ApiFlightWithSplits(
        apiFlight(3, "SA326", "SA0326", "2017-01-01T20:00:00Z", 100, 100, None, "T1", "JHB", "SA", "UNK", "2017-01-01T20:00:00Z"),
        List(ApiSplits(
          List(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 30),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 30),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 30),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 10)
          ), SplitRatiosNs.SplitSources.ApiSplitsWithCsvPercentage))
      )
    )

    val result = flightsWithSplitsToCSV(flights)

    val expected =
      """ |IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Arrival,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,API EEA Machine Readable to EGate,API EEA Machine Readable to Desk,API EEA Non Machine Readable to Desk,API Visa National to Desk, API Non-visa National to Desk,API Visa National to Fast-Track,API Non-visa National to Fast Track,Historic EEA Machine Readable to EGate,Historic EEA Machine Readable to Desk,Historic EEA Non Machine Readable to Desk,Historic Visa National to Desk, Historic Non-visa National to Desk,Historic Visa National to Fast-Track,Historic Non-visa National to Fast Track
          |SA0324,SA0324,JHB,/,UNK,2017-01-01T20:00:00Z,2017-01-01T20:00:00Z,,,,2017-01-01T20:00:00Z,2,1,3,5,4,7,6,9,8,10,12,11,14,13
          |SA0325,SA0325,JHB,/,UNK,2017-01-01T20:00:00Z,2017-01-01T20:00:00Z,,,,2017-01-01T20:00:00Z,30,30,30,,10,,,,,,,,,
          |SA0326,SA0326,JHB,/,UNK,2017-01-01T20:00:00Z,2017-01-01T20:00:00Z,,,,2017-01-01T20:00:00Z,30,30,30,,10,,,,,,,,,""".stripMargin

    result === expected
  }
}
