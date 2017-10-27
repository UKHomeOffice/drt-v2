package services

import controllers.Forecast
import drt.shared.CrunchApi.{CrunchMinute, ForecastHeadlineFigures, QueueHeadline}
import drt.shared.Queues
import org.specs2.mutable.Specification
import scala.collection.immutable.Seq

class ForecastHeadlineFiguresSpec extends Specification {

  "Given one Crunch Minutes for EeaDesk with 1 passenger at 2017-01-02T00:00Z" >> {
    "And I ask for headline figures for week beginning 2017-01-02T00:00Z for T1 " +
      "Then I should see Total Pax of 1 and EEA Pax of 1 and total workload of 2 on 2017-01-02" >> {
      val startMinute = SDate("2017-01-02T00:00Z").millisSinceEpoch
      val cms = Set(CrunchMinute("T1", Queues.EeaDesk, startMinute, 1, 2, 0, 0))
      val result = Forecast.headLineFigures(cms, "T1")

      val expected = ForecastHeadlineFigures(Set(QueueHeadline(startMinute, Queues.EeaDesk, 1, 2)))
      result === expected
    }
  }

  "Given one Crunch Minutes for EeaDesk with 1 passenger at 2017-01-02T00:00Z for T1 and another for T2" >> {
    "And I ask for headline figures for week beginning 2017-01-02T00:00Z for T1 " +
      "Then I should only see the values from T1" >> {
      val startMinute = SDate("2017-01-02T00:00Z").millisSinceEpoch
      val cms = Set(
        CrunchMinute("T1", Queues.EeaDesk, startMinute, 1, 2, 0, 0),
        CrunchMinute("T2", Queues.EeaDesk, startMinute, 1, 2, 0, 0)
      )
      val result = Forecast.headLineFigures(cms, "T1")

      val expected = ForecastHeadlineFigures(Set(QueueHeadline(startMinute, Queues.EeaDesk, 1, 2)))

      result === expected
    }
  }

  "Given two Crunch Minutes for EeaDesk and EGate with 1 passenger at 2017-01-02T00:00Z" >> {
    "And I ask for headline figures for week beginning 2017-01-02T00:00Z for T1 " +
      "Then I should see Total Pax of 1 and EEA Pax of 1 and total workload of 2 on 2017-01-02" >> {
      val startMinute = SDate("2017-01-02T00:00Z").millisSinceEpoch
      val cms = Set(
        CrunchMinute("T1", Queues.EeaDesk, startMinute, 1, 2, 0, 0),
        CrunchMinute("T1", Queues.EGate, startMinute, 1, 2, 0, 0)
      )
      val result = Forecast.headLineFigures(cms, "T1")

      val expected = ForecastHeadlineFigures(Set(
        QueueHeadline(startMinute, Queues.EeaDesk, 1, 2),
        QueueHeadline(startMinute, Queues.EGate, 1, 2)
      ))

      result === expected
    }
  }

  "Given two Crunch Minutes for EeaDesk and EGate with 1 passenger at 2017-01-02T00:00Z and at 2017-01-03T00:00Z" >> {
    "And I ask for headline figures for week beginning 2017-01-02T00:00Z for T1 " +
      "Then I should see Total Pax of 1 and EEA Pax of 1 and total workload of 2 on 2017-01-02 and 2017-01-03" >> {

      val day1StartMinute = SDate("2017-01-02T00:00Z").millisSinceEpoch
      val day2StartMinute = SDate("2017-01-03T00:00Z").millisSinceEpoch
      val cms = Set(
        CrunchMinute("T1", Queues.EeaDesk, day1StartMinute, 1, 2, 0, 0),
        CrunchMinute("T1", Queues.EGate, day1StartMinute, 1, 2, 0, 0),
        CrunchMinute("T1", Queues.EeaDesk, day2StartMinute, 1, 2, 0, 0),
        CrunchMinute("T1", Queues.EGate, day2StartMinute, 1, 2, 0, 0)
      )
      val result = Forecast.headLineFigures(cms, "T1")

      val expected = ForecastHeadlineFigures(Set(
        QueueHeadline(day1StartMinute, Queues.EeaDesk, 1, 2),
        QueueHeadline(day1StartMinute, Queues.EGate, 1, 2),
        QueueHeadline(day2StartMinute, Queues.EeaDesk, 1, 2),
        QueueHeadline(day2StartMinute, Queues.EGate, 1, 2)
      ))
      result === expected
    }
  }
}
