package drt.client.components.charts

import drt.client.components.ChartJSComponent.ChartJsData
import drt.client.components.charts.DataFormat.jsonString
import drt.client.services.ChartData.splitToNationalityChartData
import drt.client.services.{ChartData, ChartDataSet}
import drt.shared.{ApiPaxTypeAndQueueCount, PaxAge, PaxTypes, Queues}
import utest.{TestSuite, _}

object PaxSplitsDataForAgeChartTests extends TestSuite {


  def tests = Tests {
    "When extracting Age data to display in a chart" - {
      "Given Splits containing an ApiSplit with 1 passenger aged 11" +
        "Then I should get back chart data with 1 passenger in the 0-11 age range" - {

        val apiSplit = Set(ApiPaxTypeAndQueueCount(
          PaxTypes.EeaMachineReadable,
          Queues.EGate, 1,
          None,
          Option(Map(PaxAge(11) -> 1))
        ))


        val labels = Seq("0-11", "12-24", "25-49", "50-65", ">65")
        val data = Seq(1.0, 0, 0, 0, 0)

        val expected = ChartJsData(labels, data, "All Queues").toJs

        val result = ChartData.splitDataToAgeRanges(apiSplit).toJs

        assert(jsonString(result) == jsonString(expected))
      }

      //      "Given Splits containing an ApiSplit with 3 passengers aged 11, 12 and 30" +
      //        "Then I should get back chart data with 1 passenger each in the 0-11, 11-24 and 25-40 ranges" - {
      //
      //        val apiSplit = Set(ApiPaxTypeAndQueueCount(
      //          PaxTypes.EeaMachineReadable,
      //          Queues.EGate, 1,
      //          None,
      //          Option(Map(
      //            PaxAge(11) -> 1,
      //            PaxAge(12) -> 1,
      //            PaxAge(30) -> 1
      //          ))
      //        ))
      //
      //        val result = ChartData.splitDataToAgeRanges(apiSplit)
      //
      //        val expected = ChartDataSet("Passenger Ages",
      //          List(
      //            ("0-11", 1.0),
      //            ("12-24", 1.0),
      //            ("25-49", 1.0),
      //            ("50-65", 0),
      //            (">65", 0)
      //          ))
      //
      //        assert(result == expected)
      //      }
      //    }
      //
      //    "Given Splits containing multiple ApiSplits" +
      //      "Then passengers should be summed across age ranges for each split" - {
      //
      //      val apiSplit = Set(
      //        ApiPaxTypeAndQueueCount(
      //          PaxTypes.EeaMachineReadable,
      //          Queues.EGate, 1,
      //          None,
      //          Option(Map(
      //            PaxAge(11) -> 1,
      //            PaxAge(12) -> 1,
      //            PaxAge(30) -> 2
      //          ))
      //        ),
      //        ApiPaxTypeAndQueueCount(
      //          PaxTypes.EeaMachineReadable,
      //          Queues.EGate, 1,
      //          None,
      //          Option(Map(
      //            PaxAge(11) -> 2,
      //            PaxAge(12) -> 1,
      //            PaxAge(30) -> 1,
      //            PaxAge(55) -> 1,
      //            PaxAge(100) -> 1,
      //            PaxAge(99) -> 1
      //          ))
      //        )
      //      )
      //
      //      val result = ChartData.splitDataToAgeRanges(apiSplit)
      //
      //      val expected = ChartDataSet("Passenger Ages",
      //        List(
      //          ("0-11", 3.0),
      //          ("12-24", 2.0),
      //          ("25-49", 3.0),
      //          ("50-65", 1),
      //          (">65", 2)
      //        ))
      //
      //      assert(result == expected)
      //    }
    }
  }
}
