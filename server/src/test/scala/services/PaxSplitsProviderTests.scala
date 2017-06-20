package services

import drt.services.AirportConfigHelpers
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._
import org.specs2.mutable.SpecificationLike
import passengersplits.core.PassengerInfoRouterActor
import controllers.ArrivalGenerator.apiFlight

import scala.collection.mutable

class PaxSplitsProviderTests extends SpecificationLike with AirportConfigHelpers {

  "Voyage Number should be padded to 4 digits" >> {
    "3 digits should pad to 4" in {
      PassengerInfoRouterActor.padTo4Digits("123") === "0123"
    }
    "4 digits should remain 4 " in {
      PassengerInfoRouterActor.padTo4Digits("0123") === "0123"
    }
    "we think 5 is invalid, but we should return unharmed" in {
      PassengerInfoRouterActor.padTo4Digits("45123") === "45123"
    }
  }

  "Splits from multiple providers" >> {

    "Given 1 provider with splits for a flight, when we ask for splits then we should see Some()" >> {
      def provider(apiFlight: Arrival) = Some[SplitRatios](SplitRatios(TestAirportConfig))

      val providers: List[(Arrival) => Some[SplitRatios]] = List(provider)

      val flight = apiFlight(flightId = 1, iata = "BA0001", schDt = "2016-01-01T00:00:00")

      val result = SplitsProvider.splitsForFlight(providers)(flight)

      result.isDefined
    }

    "Given 2 providers, the 1st with splits and 2nd without, when we ask for splits then we should see Some()" >> {
      def providerWith(apiFlight: Arrival) = Some[SplitRatios](SplitRatios(TestAirportConfig))

      def providerWithout(apiFlight: Arrival) = None

      val providers: List[(Arrival) => Option[SplitRatios]] = List(providerWith, providerWithout)

      val flight = apiFlight(flightId = 1, iata = "BA0001", schDt = "2016-01-01T00:00:00")

      val result = SplitsProvider.splitsForFlight(providers)(flight)

      result.isDefined
    }

    "Given 2 providers, the 1st without splits and 2nd with, when we ask for splits then we should see Some()" >> {
      def providerWith(apiFlight: Arrival) = None

      def providerWithout(apiFlight: Arrival) = Some[SplitRatios](SplitRatios(TestAirportConfig))

      val providers: List[(Arrival) => Option[SplitRatios]] = List(providerWith, providerWithout)

      val flight = apiFlight(flightId = 1, iata = "BA0001", schDt = "2016-01-01T00:00:00")

      val result: Option[SplitRatios] = SplitsProvider.splitsForFlight(providers)(flight)

      result.isDefined
    }

    "Given a stateful, non-idempotent provider, we get the different result each time" >> {
      val ratios1 = SplitRatios(
        TestAirportConfig,
        SplitRatio(PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, "eea"), 23),
        SplitRatio(PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, "visa"), 10))
      val ratios2 = SplitRatios(
        TestAirportConfig,
        SplitRatio(PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, "eea"), 4),
        SplitRatio(PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, "visa"), 3))

      val ratios = mutable.Queue(ratios1, ratios2)

      def statefulProvider(apiFlight: Arrival): Option[SplitRatios] = {
        val head = ratios.dequeue()
        Option(head)
      }


      val providers: List[(Arrival) => Option[SplitRatios]] = List(statefulProvider)

      val flight = apiFlight(flightId = 1, iata = "BA0001", schDt = "2016-01-01T00:00:00")

      val splitsForFlight = SplitsProvider.splitsForFlight(providers) _

      val result1: Option[SplitRatios] = splitsForFlight(flight)

      assert(result1 === Some(ratios1))

      val result2: Option[SplitRatios] = splitsForFlight(flight)

      result2 == Some(ratios2)


    }
  }
}
