package drt.client.components

import drt.client.components.FlightComponents.bestPaxToDisplay
import drt.client.services.JSDateConversions.SDate
import drt.shared.Arrival
import utest._


object FlightComponentsTests extends TestSuite {
  def tests = TestSuite {

    "Given a flight with only act pax " +
      "When I ask for the pax to display " +
      "Then I get the act pax number" - {
      val actPax = 100
      val flight = ArrivalGenerator.apiFlight(flightId = 1, actPax = actPax)
      val apiExTransPax = 0
      val portCode = "STN"

      val result = bestPaxToDisplay(flight, apiExTransPax, portCode)
      val expected = actPax

      assert(result == expected)
    }

    "Given a flight with act pax and api pax with less than 20% difference " +
      "When I ask for the pax to display " +
      "Then I get the api pax number" - {
      val actPax = 100
      val flight = ArrivalGenerator.apiFlight(flightId = 1, actPax = actPax)
      val apiExTransPax = 120
      val result = bestPaxToDisplay(flight, apiExTransPax, "STN")
      val expected = apiExTransPax

      assert(result == expected)
    }

    "Given a flight with act pax and api pax with more than 20% difference " +
      "When I ask for the pax to display " +
      "Then I get the act pax number" - {
      val actPax = 100
      val flight = ArrivalGenerator.apiFlight(flightId = 1, actPax = actPax)
      val apiExTransPax = 121
      val result = bestPaxToDisplay(flight, apiExTransPax, "STN")
      val expected = actPax

      assert(result == expected)
    }
  }
}

object ArrivalGenerator {

  def apiFlight(
                 flightId: Int,
                 iata: String = "",
                 icao: String = "",
                 schDt: String = "",
                 actPax: Int = 0,
                 maxPax: Int = 0,
                 lastKnownPax: Option[Int] = None,
                 terminal: String = "T1",
                 origin: String = "",
                 operator: String = "",
                 status: String = "",
                 estDt: String = "",
                 actDt: String = "",
                 estChoxDt: String = "",
                 actChoxDt: String = "",
                 gate: String = "",
                 stand: String = "",
                 tranPax: Int = 0,
                 runwayId: String = "",
                 baggageReclaimId: String = "",
                 airportId: String = ""
               ): Arrival =
    Arrival(
      FlightID = flightId,
      rawICAO = icao,
      rawIATA = iata,
      SchDT = schDt,
      ActPax = actPax,

      Terminal = terminal,
      Origin = origin,
      Operator = operator,
      Status = status,
      EstDT = estDt,
      ActDT = actDt,
      EstChoxDT = estChoxDt,
      ActChoxDT = actChoxDt,
      Gate = gate,
      Stand = stand,
      MaxPax = maxPax,
      TranPax = tranPax,
      RunwayID = runwayId,
      BaggageReclaimId = baggageReclaimId,
      AirportID = airportId,
      PcpTime = if (schDt != "") SDate.parse(schDt).millisSinceEpoch else 0L,
      LastKnownPax = lastKnownPax,
      Scheduled = if (schDt != "") SDate.parse(schDt).millisSinceEpoch else 0L
    )
}
