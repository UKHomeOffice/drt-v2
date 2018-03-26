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

  "Given two consecutive shifts " +
    "When I ask for the PortState " +
    "Then I should see the staff available for the duration of the shifts" >> {
    val shiftStart = SDate("2017-01-01T00:00Z")

    val crunch = runCrunchGraph(
      airportConfig = airportConfig.copy(terminalNames = Seq("T1")),
      now = () => shiftStart,
      initialShifts =
        """shift a,T1,01/01/17,00:00,00:14,1
          |shift b,T1,01/01/17,00:15,00:29,2
        """.stripMargin
    )

    val expectedStaff = List.fill(15)(1) ::: List.fill(15)(2)
    val expectedMillis = (shiftStart.millisSinceEpoch to (shiftStart.millisSinceEpoch + 29 * Crunch.oneMinuteMillis) by Crunch.oneMinuteMillis).toList

    crunch.liveTestProbe.fishForMessage(2 seconds) {
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
    val shiftStart = SDate("2017-01-01T00:00Z")
    val initialShifts =
      """shift a,T1,01/01/17,00:00,00:04,0
        |shift b,T1,01/01/17,00:05,00:09,2
      """.stripMargin
    val initialFixedPoints =
      """egate monitors a,T1,01/01/17,00:00,00:14,2
        |roaming officers b,T1,01/01/17,00:15,00:29,2
      """.stripMargin

    val crunch = runCrunchGraph(
      airportConfig = airportConfig.copy(terminalNames = Seq("T1")),
      now = () => shiftStart
    )

    offerAndWait(crunch.liveShiftsInput, initialShifts)
    offerAndWait(crunch.liveFixedPointsInput, initialFixedPoints)

    val expectedAvailable = List(0, 0, 0, 0, 0, 2, 2, 2, 2, 2)
    val firstMilli = shiftStart.millisSinceEpoch
    val lastMilli = shiftStart.millisSinceEpoch + 9 * Crunch.oneMinuteMillis
    val expectedMillis = (firstMilli to lastMilli by Crunch.oneMinuteMillis).toList

    crunch.liveTestProbe.fishForMessage(2 seconds) {
      case ps: PortState =>
        val minutesInOrder = ps.staffMinutes.values.toList.sortBy(_.minute).take(10)
        val staff = minutesInOrder.map(_.available)
        val staffMillis = minutesInOrder.map(_.minute)

        (staffMillis, staff) == Tuple2(expectedMillis, expectedAvailable)
    }

    true
  }
}