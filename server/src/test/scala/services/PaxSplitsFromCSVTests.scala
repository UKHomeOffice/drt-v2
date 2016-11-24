package services

import org.specs2.mutable.SpecificationLike
import services.workloadcalculator.PassengerQueueTypes.{PaxTypes, Queues}
import services.workloadcalculator.PaxLoadAt.PaxTypeAndQueue
import services.workloadcalculator.SplitRatio

class PaxSplitsFromCSVTests extends SpecificationLike {

  import PassengerSplitsCSVReader._

  "Given a path to the CSV file" >> {
    "Then I should be able to parse the file" >> {
      val expected = Seq(
        SplitCSVRow("BA1234", "JHB", 97, 0, 2, 1, 70, 30, 100, 0, 100, 0, 100, 0, "Sunday", "January", "STN", "T1", "SA"),
        SplitCSVRow("BA1234", "JHB", 97, 0, 2, 1, 70, 30, 100, 0, 100, 0, 100, 0, "Monday", "January", "STN", "T1", "SA")
      )

      val rows = parseCSV(getClass.getResource("/passenger-splits-fixture.csv"))

      rows.take(2) == expected
    }
  }


  "Given a CSV containing pax splits" >> {
    "When I parse the CSV row then I should get a list of each split type for a flight" >> {
      val row = SplitCSVRow("BA1234", "JHB", 97, 0, 2, 1, 70, 30, 100, 0, 100, 0, 100, 0, "Sunday", "January", "STN", "T1", "SA")

      val expected = List(SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.291),
        SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 0.6789999999999999),
        SplitRatio(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 0.0),
        SplitRatio(PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk), 0.01),
        SplitRatio(PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk), 0.02))

      val result = parseRow(row)

      result == expected
    }
  }
}
