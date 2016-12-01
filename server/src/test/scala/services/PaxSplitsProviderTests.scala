package services

import controllers.NewPaxSplitsProvider
import org.specs2.mutable.SpecificationLike
import spatutorial.shared._

class PaxSplitsProviderTests extends SpecificationLike {

  def apiFlight(iataFlightCode: String, schDT: String): ApiFlight =
    ApiFlight(
      Operator = "",
      Status = "",
      EstDT = "",
      ActDT = "",
      EstChoxDT = "",
      ActChoxDT = "",
      Gate = "",
      Stand = "",
      MaxPax = 1,
      ActPax = 0,
      TranPax = 0,
      RunwayID = "",
      BaggageReclaimId = "",
      FlightID = 2,
      AirportID = "STN",
      Terminal = "1",
      ICAO = "",
      IATA = iataFlightCode,
      Origin = "",
      PcpTime = 0,
      SchDT = schDT
    )

//  import PassengerSplitsCSVReader._

  "Splits from multiple providers" >> {

    "Given 1 provider with splits for a flight, when we ask for splits then we should see Some()" >> {
      def provider(apiFlight: ApiFlight) = Some[List[SplitRatio]](List())
      val providers: List[(ApiFlight) => Some[List[SplitRatio]]] = List(provider)

      val flight = apiFlight("BA0001", "2016-01-01T00:00:00")

      val result = NewPaxSplitsProvider.splitsForFlight(providers)(flight)

      result.isDefined
    }

    "Given 2 providers, the 1st with splits and 2nd without, when we ask for splits then we should see Some()" >> {
      def providerWith(apiFlight: ApiFlight) = Some[List[SplitRatio]](List())
      def providerWithout(apiFlight: ApiFlight) = None
      val providers: List[(ApiFlight) => Option[List[SplitRatio]]] = List(providerWith, providerWithout)

      val flight = apiFlight("BA0001", "2016-01-01T00:00:00")

      val result = NewPaxSplitsProvider.splitsForFlight(providers)(flight)

      result.isDefined
    }

    "Given 2 providers, the 1st without splits and 2nd with, when we ask for splits then we should see Some()" >> {
      def providerWith(apiFlight: ApiFlight) = None
      def providerWithout(apiFlight: ApiFlight) = Some[List[SplitRatio]](List())
      val providers: List[(ApiFlight) => Option[List[SplitRatio]]] = List(providerWith, providerWithout)

      val flight = apiFlight("BA0001", "2016-01-01T00:00:00")

      val result: Option[List[SplitRatio]] = NewPaxSplitsProvider.splitsForFlight(providers)(flight)

      result.isDefined
    }
  }
}
