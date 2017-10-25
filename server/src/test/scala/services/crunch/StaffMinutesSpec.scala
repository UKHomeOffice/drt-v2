package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import services.SDate
import services.graphstages.Crunch
import services.graphstages.Crunch.getLocalLastMidnight

import scala.collection.immutable.List

class StaffMinutesSpec extends CrunchTestLike {
  "Given a flight with one passenger " +
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
      shifts =
        """shift a,T1,01/01/17,00:00,00:14,1
          |shift b,T1,01/01/17,00:15,00:29,2
        """.stripMargin
    )

    crunch.liveArrivalsInput.offer(flights)

    val result = crunch.liveTestProbe.expectMsgAnyClassOf(classOf[PortState])
    val minutesInOrder = result.staffMinutes.values.toList.sortBy(_.minute)
    val staff = minutesInOrder.map(_.staff)
    val staffMillis = minutesInOrder.map(_.minute)

    val expectedStaff = List.fill(15)(1) ::: List.fill(15)(2)
    val expectedMillis = (crunchStart.millisSinceEpoch to (crunchStart.millisSinceEpoch + 29*Crunch.oneMinuteMillis) by Crunch.oneMinuteMillis).toList

    (staffMillis, staff) === Tuple2(expectedMillis, expectedStaff)
  }
}