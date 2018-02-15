package services

import drt.shared._
import org.specs2.mutable.Specification


class ArrivalsToCSVDataTest extends Specification {

  import CSVData._
  import controllers.ArrivalGenerator.apiFlight

  "Given a list of arrivals with splits we should get back a CSV of arrival data" >> {

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

    val result = flightsWithSplitsToCSV(flights)

    val expected =
      """ |IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Arrival,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track
          |SA0324,SA0324,JHB,/,UNK,2017-01-01T20:00:00Z,2017-01-01T20:00:00Z,,,,2017-01-01T20:00:00Z,100,100,7,15,32,46,12,23,30,35,,,,
          |SA0325,SA0325,JHB,/,UNK,2017-01-01T20:00:00Z,2017-01-01T20:00:00Z,,,,2017-01-01T20:00:00Z,100,100,30,60,10,,,,,,,,,
          |SA0326,SA0326,JHB,/,UNK,2017-01-01T20:00:00Z,2017-01-01T20:00:00Z,,,,2017-01-01T20:00:00Z,100,100,30,60,10,,,,,,,,,""".stripMargin

    result === expected
  }
}
