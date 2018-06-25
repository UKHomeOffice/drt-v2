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
      val flight = ArrivalGenerator.apiFlight(flightId = Option(1), actPax = Option(actPax))
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
      val flight = ArrivalGenerator.apiFlight(flightId = Option(1), actPax = Option(actPax))
      val apiExTransPax = 120
      val result = bestPaxToDisplay(flight, apiExTransPax, "STN")
      val expected = apiExTransPax

      assert(result == expected)
    }

    "Given a flight with act pax and api pax with more than 20% difference " +
      "When I ask for the pax to display " +
      "Then I get the act pax number" - {
      val actPax = 100
      val flight = ArrivalGenerator.apiFlight(flightId = Option(1), actPax = Option(actPax))
      val apiExTransPax = 121
      val result = bestPaxToDisplay(flight, apiExTransPax, "STN")
      val expected = actPax

      assert(result == expected)
    }
  }
}

object ArrivalGenerator {

  def apiFlight(
                 flightId: Option[Int] = None,
                 iata: String = "",
                 icao: String = "",
                 schDt: String = "",
                 actPax: Option[Int] = None,
                 maxPax: Option[Int] = None,
                 lastKnownPax: Option[Int] = None,
                 terminal: String = "T1",
                 origin: String = "",
                 operator: Option[String] = None,
                 status: String = "",
                 estDt: String = "",
                 actDt: String = "",
                 estChoxDt: String = "",
                 actChoxDt: String = "",
                 gate: Option[String] = None,
                 stand: Option[String] = None,
                 tranPax: Option[Int] = None,
                 runwayId: Option[String] = None,
                 baggageReclaimId: Option[String] = None,
                 airportId: String = ""
               ): Arrival =
    Arrival(
      Operator = operator,
      Status = status,
      Estimated = if (estDt != "") Some(SDate(estDt).millisSinceEpoch) else None,
      Actual = if (actDt != "") Some(SDate(actDt).millisSinceEpoch) else None,
      EstimatedChox = if (estChoxDt != "") Some(SDate(estChoxDt).millisSinceEpoch) else None,
      ActualChox = if (actChoxDt != "") Some(SDate(actChoxDt).millisSinceEpoch) else None,
      Gate = gate,
      Stand = stand,
      MaxPax = maxPax,
      ActPax = actPax,
      TranPax = tranPax,
      RunwayID = runwayId,
      BaggageReclaimId = baggageReclaimId,
      FlightID = flightId,
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
