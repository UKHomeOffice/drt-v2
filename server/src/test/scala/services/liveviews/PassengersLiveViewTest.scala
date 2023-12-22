package services.liveviews

import drt.shared.CrunchApi.{MinutesContainer, PassengersMinute}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.db.PassengersHourlyRow
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate

import java.sql.Timestamp

class PassengersLiveViewTest extends AnyWordSpec with Matchers {
  "minutesContainerToHourlyRow given a container with one minute" should {
    "return one row with the the passengers for that minute" in {
      val minute1 = SDate("2023-12-21T15:00")

      val container = MinutesContainer(
        List(PassengersMinute(T1, EeaDesk, minute1.millisSinceEpoch, Seq(0.5), None))
      )

      val code = PortCode("LHR")

      val result = PassengersLiveView.minutesContainerToHourlyRows(code, () => 0L)(container)

      result.toSet should ===(Set(
        PassengersHourlyRow(code.iata, T1.toString, EeaDesk.toString, "2023-12-21", 15, 1, new Timestamp(0L))
      ))
    }
  }

  "minutesContainerToHourlyRow given a container with minutes spanning multiple hours" should {
    "return a row for each hour with the sum of the passengers for that hour" in {
      val hour1minute1 = SDate("2023-12-21T15:00")
      val hour1minute2 = SDate("2023-12-21T15:59")
      val hour2minute1 = SDate("2023-12-21T20:00")
      val hour2minute2 = SDate("2023-12-21T20:59")

      val container = MinutesContainer(
        List(
          PassengersMinute(T1, EeaDesk, hour1minute1.millisSinceEpoch, Seq.fill(5)(0.5), None),
          PassengersMinute(T1, EeaDesk, hour1minute2.millisSinceEpoch, Seq.fill(5)(0.5), None),
          PassengersMinute(T1, EGate, hour1minute1.millisSinceEpoch, Seq.fill(4)(0.6), None),
          PassengersMinute(T1, EGate, hour1minute2.millisSinceEpoch, Seq.fill(4)(0.6), None),
          PassengersMinute(T1, EeaDesk, hour2minute1.millisSinceEpoch, Seq.fill(3)(0.5), None),
          PassengersMinute(T1, EeaDesk, hour2minute2.millisSinceEpoch, Seq.fill(3)(0.5), None),
          PassengersMinute(T1, EGate, hour2minute1.millisSinceEpoch, Seq.fill(2)(0.6), None),
          PassengersMinute(T1, EGate, hour2minute2.millisSinceEpoch, Seq.fill(2)(0.6), None),
        )
      )

      val code = PortCode("LHR")

      val result = PassengersLiveView.minutesContainerToHourlyRows(code, () => 0L)(container)

      result.toSet should ===(Set(
        PassengersHourlyRow(code.iata, T1.toString, EeaDesk.toString, "2023-12-21", 15, 10, new Timestamp(0L)),
        PassengersHourlyRow(code.iata, T1.toString, EeaDesk.toString, "2023-12-21", 20, 6, new Timestamp(0L)),
        PassengersHourlyRow(code.iata, T1.toString, EGate.toString, "2023-12-21", 15, 8, new Timestamp(0L)),
        PassengersHourlyRow(code.iata, T1.toString, EGate.toString, "2023-12-21", 20, 4, new Timestamp(0L)),
      ))
    }
  }
}
