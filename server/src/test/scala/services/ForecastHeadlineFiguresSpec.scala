package services

import drt.shared.CrunchApi.{ForecastHeadlineFigures, QueueHeadline}
import drt.shared.PortState
import org.specs2.mutable.Specification
import services.exports.Forecast
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import uk.gov.homeoffice.drt.time.SDate

class ForecastHeadlineFiguresSpec extends Specification {

  "Given one Crunch Minutes for EeaDesk with 1 passenger at 2017-01-02T00:00Z" >> {
    "And I ask for headline figures for week beginning 2017-01-02T00:00Z for T1 " +
      "Then I should see Total Pax of 1 and EEA Pax of 1 and total workload of 2 on 2017-01-02" >> {
      val startMinute = SDate("2017-01-02T00:00Z")
      val ps = PortState(List(), List(CrunchMinute(T1, EeaDesk, startMinute.millisSinceEpoch, 1, 2, 0, 0, None)), List())
      val result = Forecast.headlineFigures(startMinute, 1, T1, ps, (_, _, _) => Seq(EeaDesk))

      val expected = ForecastHeadlineFigures(Seq(QueueHeadline(startMinute.millisSinceEpoch, EeaDesk, 1, 2)))
      result === expected
    }
  }

  "Given one Crunch Minutes for EeaDesk with 1 passenger at 2017-01-02T00:00Z for T1 and another for T2" >> {
    "And I ask for headline figures for week beginning 2017-01-02T00:00Z for T1 " +
      "Then I should only see the values from T1" >> {
      val startMinute = SDate("2017-01-02T00:00Z")
      val ps = PortState(List(), List(
        CrunchMinute(T1, EeaDesk, startMinute.millisSinceEpoch, 1, 2, 0, 0, None),
        CrunchMinute(T2, EeaDesk, startMinute.millisSinceEpoch, 1, 2, 0, 0, None)
      ), List())

      val result = Forecast.headlineFigures(startMinute, 1, T1, ps, (_, _, _) => Seq(EeaDesk))
      val expected = ForecastHeadlineFigures(Seq(QueueHeadline(startMinute.millisSinceEpoch, EeaDesk, 1, 2)))

      result === expected
    }
  }

  "Given two Crunch Minutes for EeaDesk and EGate with 1 passenger at 2017-01-02T00:00Z" >> {
    "And I ask for headline figures for week beginning 2017-01-02T00:00Z for T1 " +
      "Then I should see Total Pax of 1 and EEA Pax of 1 and total workload of 2 on 2017-01-02" >> {
      val startMinute = SDate("2017-01-02T00:00Z")
      val ps = PortState(List(), List(
        CrunchMinute(T1, EeaDesk, startMinute.millisSinceEpoch, 1, 2, 0, 0, None),
        CrunchMinute(T1, EGate, startMinute.millisSinceEpoch, 1, 2, 0, 0, None)
      ), List())
      val result = Forecast.headlineFigures(startMinute, 1, T1, ps, (_, _, _) => Seq(EeaDesk, EGate))

      val expected = ForecastHeadlineFigures(Seq(
        QueueHeadline(startMinute.millisSinceEpoch, EeaDesk, 1, 2),
        QueueHeadline(startMinute.millisSinceEpoch, EGate, 1, 2)
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
        CrunchMinute(T1, EeaDesk, day1StartMinute.millisSinceEpoch, 1, 2, 0, 0, None),
        CrunchMinute(T1, EGate, day1StartMinute.millisSinceEpoch, 1, 2, 0, 0, None),
        CrunchMinute(T1, EeaDesk, day2StartMinute.millisSinceEpoch, 1, 2, 0, 0, None),
        CrunchMinute(T1, EGate, day2StartMinute.millisSinceEpoch, 1, 2, 0, 0, None)
      ), List())
      val result = Forecast.headlineFigures(day1StartMinute, 2, T1, ps, (_, _, _) => Seq(EeaDesk, EGate))

      val expected = ForecastHeadlineFigures(Seq(
        QueueHeadline(day1StartMinute.millisSinceEpoch, EeaDesk, 1, 2),
        QueueHeadline(day1StartMinute.millisSinceEpoch, EGate, 1, 2),
        QueueHeadline(day2StartMinute.millisSinceEpoch, EeaDesk, 1, 2),
        QueueHeadline(day2StartMinute.millisSinceEpoch, EGate, 1, 2)
      ))
      result === expected
    }
  }
}
