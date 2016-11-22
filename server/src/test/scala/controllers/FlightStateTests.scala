package controllers

import spatutorial.shared._
import utest._

object FlightStateTests extends TestSuite {
  def apiFlight(flightId: Int, schDt: String, estDt: String): ApiFlight =
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
      SchDT = schDt
    )

  import services.inputfeeds.CrunchTests._

  def tests = TestSuite {
    "given a flight arriving after the start threshold, " +
      "when we look at the FlightState, " +
      "then we should see that flight" - {
      val startThreshold = "2016-01-01T12:00"
      val newFlights = List(apiFlight(flightId = 1, schDt = "2016-01-01T12:30", estDt = "2016-01-01T12:30"))

      withContext { context =>
        val result = getFlightStateFlightsListFromUpdate(context, startThreshold, newFlights)

        assert(result == newFlights)
      }
    }

    "given one flight arriving after the start threshold and one before, " +
      "when we look at the FlightState, " +
      "then we should only see the one arriving after" - {
      val startThreshold = "2016-01-01T12:00"

      val invalidFlights = List(apiFlight(flightId = 1, schDt = "2016-01-01T11:30", estDt = "2016-01-01T11:30"))
      val validFlights = List(apiFlight(flightId = 2, schDt = "2016-01-01T12:30", estDt = "2016-01-01T12:30"))
      val newFlights = validFlights ::: invalidFlights

      withContext { context =>
        val result = getFlightStateFlightsListFromUpdate(context, startThreshold, newFlights)

        assert(result == validFlights)
      }
    }

    "given one flight scheduled after the threshold but with no estimated time, " +
      "when we look at the FlightState, " +
      "then we should see that one flight" - {
      val startThreshold = "2016-01-01T12:00"
      val newFlights = List(
        apiFlight(flightId = 1, schDt = "2016-01-01T12:30", estDt = "")
      )

      withContext { context =>
        val result = getFlightStateFlightsListFromUpdate(context, startThreshold, newFlights)

        assert(result == newFlights)
      }
    }
  }

  def getFlightStateFlightsListFromUpdate(context: TestContext, startThreshold: String, newFlights: List[ApiFlight]): List[ApiFlight] = {
    val flightState = new FlightState {
      def log = context.system.log
    }

    flightState.onFlightUpdates(newFlights, FlightStateHandlers.findFlightUpdates(startThreshold, flightState.log))

    val result = flightState.flights.toList.map(_._2)
    result
  }
}





