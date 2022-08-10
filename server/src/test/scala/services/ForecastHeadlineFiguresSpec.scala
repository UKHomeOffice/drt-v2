package services

import drt.shared.CrunchApi.{CrunchMinute, ForecastHeadlineFigures, QueueHeadline}
import drt.shared.PortState
import org.specs2.mutable.Specification
import services.exports.Forecast
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}

import scala.collection.immutable.Seq

class ForecastHeadlineFiguresSpec extends Specification {

  "Given one Crunch Minutes for EeaDesk with 1 passenger at 2017-01-02T00:00Z" >> {
    "And I ask for headline figures for week beginning 2017-01-02T00:00Z for T1 " +
      "Then I should see Total Pax of 1 and EEA Pax of 1 and total workload of 2 on 2017-01-02" >> {
      val startMinute = SDate("2017-01-02T00:00Z")
      val ps = PortState(List(), List(CrunchMinute(T1, Queues.EeaDesk, startMinute.millisSinceEpoch, 1, None, 2, 0, 0, None)), List())
      val result = Forecast.headlineFigures(startMinute, 1, T1, ps, List(Queues.EeaDesk))

      val expected = ForecastHeadlineFigures(Seq(QueueHeadline(startMinute.millisSinceEpoch, Queues.EeaDesk, 1, 2)))
      result === expected
    }
  }

  "Given one Crunch Minutes for EeaDesk with 1 passenger at 2017-01-02T00:00Z for T1 and another for T2" >> {
    "And I ask for headline figures for week beginning 2017-01-02T00:00Z for T1 " +
      "Then I should only see the values from T1" >> {
      val startMinute = SDate("2017-01-02T00:00Z")
      val ps = PortState(List(), List(
        CrunchMinute(T1, Queues.EeaDesk, startMinute.millisSinceEpoch, 1, None, 2, 0, 0, None),
        CrunchMinute(T2, Queues.EeaDesk, startMinute.millisSinceEpoch, 1, None, 2, 0, 0, None)
      ), List())

      val result = Forecast.headlineFigures(startMinute, 1, T1, ps, List(Queues.EeaDesk))
      val expected = ForecastHeadlineFigures(Seq(QueueHeadline(startMinute.millisSinceEpoch, Queues.EeaDesk, 1, 2)))

      result === expected
    }
  }

  "Given two Crunch Minutes for EeaDesk and EGate with 1 passenger at 2017-01-02T00:00Z" >> {
    "And I ask for headline figures for week beginning 2017-01-02T00:00Z for T1 " +
      "Then I should see Total Pax of 1 and EEA Pax of 1 and total workload of 2 on 2017-01-02" >> {
      val startMinute = SDate("2017-01-02T00:00Z")
      val ps = PortState(List(), List(
        CrunchMinute(T1, Queues.EeaDesk, startMinute.millisSinceEpoch, 1, None, 2, 0, 0, None),
        CrunchMinute(T1, Queues.EGate, startMinute.millisSinceEpoch, 1, None, 2, 0, 0, None)
      ), List())
      val result = Forecast.headlineFigures(startMinute, 1, T1, ps, List(Queues.EeaDesk, Queues.EGate))

      val expected = ForecastHeadlineFigures(Seq(
        QueueHeadline(startMinute.millisSinceEpoch, Queues.EeaDesk, 1, 2),
        QueueHeadline(startMinute.millisSinceEpoch, Queues.EGate, 1, 2)
      ))

      result === expected
    }
  }

  "Given two Crunch Minutes for EeaDesk and EGate with 1 passenger at 2017-01-02T00:00Z and at 2017-01-03T00:00Z" >> {
    "And I ask for headline figures for week beginning 2017-01-02T00:00Z for T1 " +
      "Then I should see Total Pax of 1 and EEA Pax of 1 and total workload of 2 on 2017-01-02 and 2017-01-03" >> {

      val day1StartMinute = SDate("2017-01-02T00:00Z")
      val day2StartMinute = SDate("2017-01-03T00:00Z")
      val ps = PortState(List(), List(
        CrunchMinute(T1, Queues.EeaDesk, day1StartMinute.millisSinceEpoch, 1, None, 2, 0, 0, None),
        CrunchMinute(T1, Queues.EGate, day1StartMinute.millisSinceEpoch, 1, None, 2, 0, 0, None),
        CrunchMinute(T1, Queues.EeaDesk, day2StartMinute.millisSinceEpoch, 1, None, 2, 0, 0, None),
        CrunchMinute(T1, Queues.EGate, day2StartMinute.millisSinceEpoch, 1, None, 2, 0, 0, None)
      ), List())
      val result = Forecast.headlineFigures(day1StartMinute, 2, T1, ps, List(Queues.EeaDesk, Queues.EGate))

      val expected = ForecastHeadlineFigures(Seq(
        QueueHeadline(day1StartMinute.millisSinceEpoch, Queues.EeaDesk, 1, 2),
        QueueHeadline(day1StartMinute.millisSinceEpoch, Queues.EGate, 1, 2),
        QueueHeadline(day2StartMinute.millisSinceEpoch, Queues.EeaDesk, 1, 2),
        QueueHeadline(day2StartMinute.millisSinceEpoch, Queues.EGate, 1, 2)
      ))
      result === expected
    }
  }
}
