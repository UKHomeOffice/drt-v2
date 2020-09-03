package drt.client.components.charts

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

        val result = ChartData.splitToPaxTypeData(apiSplit)

        val expected = ChartDataSet("Passenger Types", List(("EEA Machine Readable", 1.0)))

        assert(result == expected)
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

        val result = ChartData.splitToPaxTypeData(apiSplit)

        val expected = ChartDataSet(
          "Passenger Types",
          Seq(
            ("B5J+ National", 4.0),
            ("EEA Child", 1.0),
            ("EEA Machine Readable", 10.0),
            ("Non-Visa National", 2.0),
            ("Visa National", 7.0)
          ),
        )


        assert(result == expected)
      }
    }

  }
}
