package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import services.SDate
import services.graphstages.Crunch
import services.graphstages.Crunch.getLocalLastMidnight

import scala.collection.immutable.List
import scala.concurrent.duration._

class StaffMinutesSpec extends CrunchTestLike {
  sequential
  isolated

  "Given a flight with one passenger, and a shift that covers the pcp time " +
    "When I ask for the PortState " +
    "Then I should see the staff available for the duration of the triggered crunch" >> {
    val scheduled = "2017-01-01T00:00Z"

    val flights = Flights(List(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 1)
    ))

    val crunchStart = SDate(scheduled)

    val crunch = runCrunchGraph(
      now = () => crunchStart,
      crunchStartDateProvider = (_) => getLocalLastMidnight(crunchStart),
      crunchEndDateProvider = (_) => getLocalLastMidnight(crunchStart).addMinutes(30),
      initialShifts =
        """shift a,T1,01/01/17,00:00,00:14,1
          |shift b,T1,01/01/17,00:15,00:29,2
        """.stripMargin
    )

    crunch.liveArrivalsInput.offer(flights)

    val expectedStaff = List.fill(15)(1) ::: List.fill(15)(2)
    val expectedMillis = (crunchStart.millisSinceEpoch to (crunchStart.millisSinceEpoch + 29 * Crunch.oneMinuteMillis) by Crunch.oneMinuteMillis).toList

    crunch.liveTestProbe.fishForMessage(5 seconds) {
      case ps: PortState =>
        val minutesInOrder = ps.staffMinutes.values.toList.sortBy(_.minute)
        val staff = minutesInOrder.map(_.shifts)
        val staffMillis = minutesInOrder.map(_.minute)

        (staffMillis, staff) == Tuple2(expectedMillis, expectedStaff)
    }

    true
  }

  "Given a flight with one passenger, zero staff from shifts and 2 fixed points " +
    "When I ask for the PortState " +
    "Then I should see zero staff available rather than a negative number" >> {
    val scheduled = "2017-01-01T00:00Z"

    val flights = Flights(List(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 1)
    ))

    val crunchStart = SDate(scheduled)

    val crunch = runCrunchGraph(
      now = () => crunchStart,
      crunchStartDateProvider = (_) => getLocalLastMidnight(crunchStart),
      crunchEndDateProvider = (_) => getLocalLastMidnight(crunchStart).addMinutes(30),
      initialShifts =
        """shift a,T1,01/01/17,00:00,00:14,0
          |shift b,T1,01/01/17,00:15,00:29,2
        """.stripMargin,
      initialFixedPoints =
        """egate monitors a,T1,01/01/17,00:00,00:14,2
          |roaming officers b,T1,01/01/17,00:15,00:29,2
        """.stripMargin
    )

    crunch.liveArrivalsInput.offer(flights)

    val expectedStaff = List.fill(15)(0) ++ List.fill(15)(2)
    val expectedMillis = (crunchStart.millisSinceEpoch to (crunchStart.millisSinceEpoch + 29 * Crunch.oneMinuteMillis) by Crunch.oneMinuteMillis).toList

    crunch.liveTestProbe.fishForMessage(5 seconds) {
      case ps: PortState =>
        val minutesInOrder = ps.staffMinutes.values.toList.sortBy(_.minute)
        val staff = minutesInOrder.map(_.available)
        val staffMillis = minutesInOrder.map(_.minute)

        (staffMillis, staff) === Tuple2(expectedMillis, expectedStaff)
    }

    true
  }
}