package services.arrivals

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import drt.shared.Terminals.{A1, A2}
import drt.shared.api.Arrival
import drt.shared.redlist.RedListUpdates
import drt.shared.{AirportConfigs, PortCode, PortState}
import server.feeds.ArrivalsFeedSuccess
import services.SDate
import services.crunch.{CrunchGraphInputsAndProbes, CrunchTestLike, TestConfig, TestDefaults}

import scala.concurrent.duration._

class EdiFlightAdjustmentsStreamSpec extends CrunchTestLike {
  var portIsRedListed = false
  val isRedListed: (PortCode, MillisSinceEpoch, RedListUpdates) => Boolean = (_, _, _) => portIsRedListed
  val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(
    now = () => SDate("2019-01-01T01:00"),
    pcpArrivalTime = TestDefaults.pcpForFlightFromBest,
    airportConfig = AirportConfigs.confByPort(PortCode("EDI")),
    arrivalsAdjustments = EdiArrivalsTerminalAdjustments(isRedListed)
  ))
  val arrivalOne: Arrival = ArrivalGenerator.arrival(iata = "TST001", terminal = A1, origin = PortCode("JFK"), schDt = "2019-01-01T00:00", actPax = Option(100))

  "Given a flight from an amber country" >> {
    "Then we should see the flight in A2 in the port state and not in A1" >> {
      val result = offerAndCheck(arrivalOne)
      portIsRedListed = true

      result.flights(arrivalOne.unique.copy(terminal = A2)).apiFlight.ActPax === arrivalOne.ActPax
      result.flights.get(arrivalOne.unique) === None
      result.flights.size mustEqual 1
    }
  }

  "Given a flight from an amber country followed by the same flight after its country has been put on the red list" >> {
    "Then we should see the flight in A1 in the port state and not in A2" >> {
      offerAndCheck(arrivalOne)
      portIsRedListed = true

      val updatedActPax = Option(110)
      val result = offerAndCheck(arrivalOne.copy(ActPax = updatedActPax))

      println(s"flights: ${result.flights}")

      result.flights(arrivalOne.unique).apiFlight.ActPax === updatedActPax
      result.flights.get(arrivalOne.unique.copy(terminal = A2)) === None
      result.flights.size mustEqual 1
    }
  }

  private def offerAndCheck(arrival: Arrival): PortState = {
    val adjustedArrivals = EdiArrivalsTerminalAdjustments(isRedListed).apply(List(arrival), RedListUpdates.empty)
    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(adjustedArrivals)))

    crunch.portStateTestProbe.fishForMessage(1.second) {
      case ps: PortState =>
        ps.flights.values.exists(a => a.apiFlight.ActPax == arrival.ActPax)
    }.asInstanceOf[PortState]
  }

}
