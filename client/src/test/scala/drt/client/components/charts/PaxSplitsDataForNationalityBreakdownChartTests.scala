package drt.client.components.charts

import drt.client.components.ChartJSComponent.{ChartJsData, ChartJsDataSet}
import drt.client.components.charts.DataFormat.jsonString
import drt.client.services.ChartData.splitToNationalityChartData
import drt.client.services.ChartDataSet
import drt.shared.PaxTypes._
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk}
import drt.shared.{ApiPaxTypeAndQueueCount, Nationality, PaxAge, PaxTypes, Queues}
import utest.{TestSuite, _}

object PaxSplitsDataForNationalityBreakdownChartTests extends TestSuite {

  def tests = Tests {
    "When extracting nationality breakdown to display in a chart" - {
      "Given Splits containing an ApiSplit with 1 passenger split with a nationality of GB " +
        "Then I should get a Map of" - {

        val apiSplit = Set(ApiPaxTypeAndQueueCount(
          PaxTypes.EeaMachineReadable,
          Queues.EGate, 1,
          Option(Map(Nationality("GBR") -> 1.0)),
          Option(Map(PaxAge(21) -> 1.0))
        ))

        val expected = ChartJsData(Seq("GBR"), Seq(1.0), "All Queues").toJs

        val result = splitToNationalityChartData(apiSplit).toJs

        assert(jsonString(result) == jsonString(expected))
      }
    }

    "When extracting nationality breakdown to display in a chart" - {
      "Given Splits containing an ApiSplit with GB passengers in multiple queues " +
        "Then I should the total of all GB Pax for that nationality" - {

        val apiSplit = Set(
          ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 3.0, Some(Map(Nationality("GBR") -> 2.0)), None),
          ApiPaxTypeAndQueueCount(EeaBelowEGateAge, EeaDesk, 1, Some(Map(Nationality("GBR") -> 1)), None),
          ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 8.0, Some(Map(Nationality("GBR") -> 8.0)), None),
        )

        val expected = ChartJsData(Seq("GBR"), Seq(11.0), "All Queues").toJs

        val result = splitToNationalityChartData(apiSplit).toJs

        assert(jsonString(result) == jsonString(expected))
      }
    }

    "When extracting nationality breakdown to display in a chart" - {
      "Given Splits containing an ApiSplit with multiple nationalities in multiple queues " +
        "Then I should the total of each nationality across all queues" - {

        val apiSplit = Set(
          ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 2, Some(Map(Nationality("MRU") -> 2)), None),
          ApiPaxTypeAndQueueCount(B5JPlusNational, EGate, 2.4, Some(Map(Nationality("AUS") -> 2)), None),
          ApiPaxTypeAndQueueCount(EeaBelowEGateAge, EeaDesk, 1, Some(Map(Nationality("GBR") -> 1)), None),
          ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 7, Some(Map(Nationality("GBR") -> 8)), None),
          ApiPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 7, Some(Map(Nationality("ZWE") -> 7)), None),
          ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 3, Some(Map(Nationality("GBR") -> 8)), None),
          ApiPaxTypeAndQueueCount(B5JPlusNational, EeaDesk, 2, Some(Map(Nationality("AUS") -> 2)), None)
        )

        val data = Seq(4.0, 17.0, 2.0, 7.0)
        val labels = Seq("AUS", "GBR", "MRU", "ZWE")

        val expected = ChartJsData(labels, data, "All Queues").toJs

        val result = splitToNationalityChartData(apiSplit).toJs

        assert(jsonString(result) == jsonString(expected))
      }
    }

    "When extracting nationality breakdown to display in a chart" - {
      "Given an ApiSplit with 1 GB and 1 US I should get a list of [(GB, 1), (US, 1)] in alphabetical order" - {

        val apiSplit = Set(ApiPaxTypeAndQueueCount(
          PaxTypes.EeaMachineReadable,
          Queues.EGate, 1,
          Option(
            Map(
              Nationality("ITA") -> 1.0,
              Nationality("GBR") -> 1.0
            )
          ),
          None
        ))

        val labels = Seq("GBR", "ITA")
        val data = Seq(1.0, 1.0)
        val expected = ChartJsData(labels, data, "All Queues").toJs

        val result = splitToNationalityChartData(apiSplit).toJs

        assert(jsonString(result) == jsonString(expected))
      }
    }
  }
}
