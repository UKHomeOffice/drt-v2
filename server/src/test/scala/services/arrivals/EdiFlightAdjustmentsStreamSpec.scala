package services.arrivals

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.Terminals.{A1, A2}
import drt.shared.api.Arrival
import drt.shared.{AirportConfigs, PortCode, PortState}
import server.feeds.ArrivalsFeedSuccess
import services.SDate
import services.crunch.{CrunchGraphInputsAndProbes, CrunchTestLike, TestConfig, TestDefaults}

import scala.concurrent.duration._

class EdiFlightAdjustmentsStreamSpec extends CrunchTestLike {
  val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(
    now = () => SDate("2019-01-01T01:00"),
    pcpArrivalTime = TestDefaults.pcpForFlightFromBest,
    airportConfig = AirportConfigs.confByPort(PortCode("EDI")),
    arrivalsAdjustments = EdiArrivalsTerminalAdjustments(Map())
  ))
  val arrivalOne: Arrival = ArrivalGenerator.arrival(iata = "TST001", terminal = A1, origin = PortCode("JFK"), schDt = "2019-01-01T00:00", actPax = Option(100))

  val arrivalTwo: Arrival = ArrivalGenerator.arrival(iata = "TST001", terminal = A1, origin = PortCode("JFK"), schDt = "2019-01-01T00:00", actPax = Option(117), baggageReclaimId = Option("7"))

  "Given a flight with a terminal mapped to A1 followed by the same flight with a baggage carousel of 7" >> {
    "Then we should see the flight in A2 in the port state and not in A1" >> {

      offerAndCheck(arrivalOne)
      val result = offerAndCheck(arrivalTwo)

      println(s"${result.flights.keys}")

      result.flights(arrivalTwo.unique.copy(terminal = A2)).apiFlight.ActPax === arrivalTwo.ActPax
      result.flights.get(arrivalOne.unique) === None
      result.flights.size mustEqual (1)
    }
  }

  "Given a flight with a baggage carousel of 7 that receives an update to the passengers" >> {
    "Then we should see the flight in A2 in the port state" >> {

      offerAndCheck(arrivalTwo)
      val result = offerAndCheck(arrivalTwo.copy(ActPax = Option(7)))

      result.flights(arrivalTwo.unique.copy(terminal = A2)).apiFlight.ActPax === Option(7)
    }
  }

  private def offerAndCheck(arrival: Arrival): PortState = {
    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(List(arrival))))

    crunch.portStateTestProbe.fishForMessage(1 second) {
      case ps: PortState =>
        ps.flights.values.exists(a => a.apiFlight.ActPax == arrival.ActPax)
    }.asInstanceOf[PortState]
  }

}
