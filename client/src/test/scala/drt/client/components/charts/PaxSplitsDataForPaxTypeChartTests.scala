package drt.client.components.charts

import drt.client.components.ChartJSComponent.ChartJsData
import drt.client.components.charts.DataFormat.jsonString
import drt.client.services.charts.ChartData
import drt.shared.PaxTypes._
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk}
import drt.shared.{ApiPaxTypeAndQueueCount, PaxTypes, Queues}
import uk.gov.homeoffice.drt.Nationality
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
        "Then I should get the total of each passenger type across all queues" - {

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

  }
}
