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
      Operator = Some(operator),
      Status = status,
      Estimated = if (estDt != "") Some(SDate(estDt).millisSinceEpoch) else None,
      Actual = if (actDt != "") Some(SDate(actDt).millisSinceEpoch) else None,
      EstimatedChox = if (estChoxDt != "") Some(SDate(estChoxDt).millisSinceEpoch) else None,
      ActualChox = if (actChoxDt != "") Some(SDate(actChoxDt).millisSinceEpoch) else None,
      Gate = if (gate!= "") Some(gate) else None,
      Stand = if (stand!= "") Some(stand) else None,
      MaxPax = if (maxPax!=0) Some(maxPax) else None,
      ActPax = if (actPax!=0) Some(actPax) else None,
      TranPax = if (tranPax!= 0) Some(tranPax) else None,
      RunwayID = if (runwayId != "") Some(runwayId) else None,
      BaggageReclaimId = if (baggageReclaimId!= "") Some(baggageReclaimId) else None,
      FlightID = if (flightId!= 0) Some(flightId) else None,
      AirportID = airportId,
      Terminal = terminal,
      rawICAO = icao,
      rawIATA = iata,
      Origin = origin,
      PcpTime = if (schDt != "") Some(SDate.parse(schDt).millisSinceEpoch) else None,
      Scheduled = if (schDt != "") SDate.parse(schDt).millisSinceEpoch else 0L,
      LastKnownPax = lastKnownPax
    )
}
