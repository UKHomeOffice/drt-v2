package drt.client.components.charts

import drt.client.components.ChartJSComponent.ChartJsData
import drt.client.components.charts.DataFormat.jsonString
import drt.client.services.ChartData.applySplitsToTotal
import drt.client.services.{ChartData, ChartDataSet}
import drt.shared.PaxTypes._
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk}
import drt.shared.{ApiPaxTypeAndQueueCount, Nationality, PaxTypes, Queues}
import utest.{TestSuite, _}

object PaxSplitsDataForPaxTypeChartTests extends TestSuite {


  def tests = Tests {
    "When extracting PaxType data to display in a chart" - {
      "Given Splits containing an ApiSplit with 1 passenger split of type EEA Machine Readable " +
        "Then I should get back chart data the same" - {

        val apiSplit = Set(ApiPaxTypeAndQueueCount(
          PaxTypes.EeaMachineReadable,
          Queues.EGate, 1,
          Option(Map(Nationality("GBR") -> 1.0)), None
        ))

        val labels = Seq("EEA Machine Readable")
        val data = Seq(1.0)

        val expected = ChartJsData(labels, data, "Passenger Types").toJs
        val result = ChartData.splitToPaxTypeData(apiSplit).toJs

        assert(jsonString(result) == jsonString(expected))
      }
    }

    "When extracting passenger type breakdown to display in a chart" - {
      "Given Splits containing an ApiSplit with multiple passenger types in multiple queues " +
        "Then I should the total of each passenger type across all queues" - {

        val apiSplit = Set(
          ApiPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 7, None, None),
          ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 2, None, None),
          ApiPaxTypeAndQueueCount(B5JPlusNational, EGate, 2, None, None),
          ApiPaxTypeAndQueueCount(EeaBelowEGateAge, EeaDesk, 1, None, None),
          ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 7, None, None),
          ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 3, None, None),
          ApiPaxTypeAndQueueCount(B5JPlusNational, EeaDesk, 2, None, None)
        )

        val labels = Seq("B5J+ National", "EEA Child", "EEA Machine Readable", "Non-Visa National", "Visa National")
        val data = Seq(4.0, 1.0, 10.0, 2.0, 7.0)

        val expected = ChartJsData(labels, data, "Passenger Types").toJs
        val result = ChartData.splitToPaxTypeData(apiSplit).toJs

        assert(jsonString(result) == jsonString(expected))
      }
    }

    "When displaying historic split quantities they should be applied to the expected total pax for the flight" - {
      "Given 1 passenger split the entire total should be allocated to that split" - {
//        val splitData = Seq(("EEA Machine Readable", 10.0))
//
//        val result = applySplitsToTotal(splitData, 100)
//
//        val expected = Seq(("EEA Machine Readable", 100))
//
//        assert(result == expected)
//      }
//      "Given 1 EEA MR, 2 EEA NMR and 30 passengers then the split should be 10 MR and 20 NMR" - {
//        val splitData = Seq(
//          ("EEA Machine Readable", 1.0),
//          ("EEA Non-Machine Readable", 2.0)
//        )
//
//        val expected = Seq(
//          ("EEA Machine Readable", 10),
//          ("EEA Non-Machine Readable", 20)
//        )
//
//        val labels = Seq("EEA Machine Readable", "EEA Non-Machine Readable")
//        val data = Seq(10, 20)
//        val expected = ChartJsData(labels, data, "Passenger Types").toJs
//
//        val result = applySplitsToTotal(splitData, 30).toJs
//
//
//        assert(jsonString(result) == jsonString(expected))
//      }
    }

  }
}
