package controllers

import spatutorial.shared._
import utest._

object FlightStateTests extends TestSuite {
  def apiFlight(estDt: String, flightId: Int): ApiFlight =
    ApiFlight(
      Operator = "",
      Status = "",
      EstDT = estDt,
      ActDT = "",
      EstChoxDT = "",
      ActChoxDT = "",
      Gate = "",
      Stand = "",
      MaxPax = 0,
      ActPax = 0,
      TranPax = 0,
      RunwayID = "",
      BaggageReclaimId = "",
      FlightID = flightId,
      AirportID = "",
      Terminal = "",
      ICAO = "",
      IATA = "",
      Origin = "",
      PcpTime = 0,
      SchDT = ""
    )

  def tests = TestSuite {
    "given a flight arriving after the start threshold when we look at the FlightState then we should see that flight" - {
      val startThreshold = "2016-01-01T12:00"
      val newFlights = List(apiFlight(estDt = "2016-01-01T12:30", flightId = 1))

      import services.inputfeeds.CrunchTests._

      withContext { context =>
        val flightState = new FlightState {
          def log = context.system.log
        }

        flightState.onFlightUpdates(newFlights, AllInOnebucket.findFlightUpdates(startThreshold, flightState.log))

        assert(flightState.flights.toList.length == 1)
        assert(flightState.flights == newFlights.map(x => (x.FlightID, x)).toMap)
      }
    }

    "given one flight arriving after the start threshold and one before when we look at the FlightState then we should only see the one arriving after" - {
      val startThreshold = "2016-01-01T12:00"
      val newFlights = List(
        apiFlight(estDt = "2016-01-01T11:30", flightId = 1),
        apiFlight(estDt = "2016-01-01T12:30", flightId = 2)
      )

      import services.inputfeeds.CrunchTests._

      withContext { context =>
        val flightState = new FlightState {
          def log = context.system.log
        }

        flightState.onFlightUpdates(newFlights, AllInOnebucket.findFlightUpdates(startThreshold, flightState.log))

        assert(flightState.flights.toList.length == 1)
        assert(flightState.flights == newFlights.filter(_.EstDT > startThreshold).map(x => (x.FlightID, x)).toMap)
      }
    }
  }
}





