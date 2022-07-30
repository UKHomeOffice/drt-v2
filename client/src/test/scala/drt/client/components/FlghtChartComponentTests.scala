package drt.client.components

import uk.gov.homeoffice.drt.Nationality
import utest.{TestSuite, _}

object FlghtChartComponentTests extends TestSuite {

  def tests = Tests {

    "Given 5 nationalities when I roll up to 10, I should get back all 5" - {

      val nats = Map(
        Nationality("GBR") -> 4,
        Nationality("ITA") -> 4,
        Nationality("ZWE") -> 4,
        Nationality("ZAF") -> 4,
        Nationality("AUS") -> 4,
      )
      val result = FlightChartComponent.summariseNationalities(nats, 10)

      assert(result == nats)
    }

    "Given 5 nationalities when I roll up to 4, I should get back the top 4, and other" - {

      val nats = Map(
        Nationality("GBR") -> 1,
        Nationality("ITA") -> 2,
        Nationality("ZWE") -> 3,
        Nationality("ZAF") -> 4,
        Nationality("AUS") -> 5,
      )

      val result = FlightChartComponent.summariseNationalities(nats, 4)

      val expected = Map(
        Nationality("Other") -> 1,
        Nationality("ITA") -> 2,
        Nationality("ZWE") -> 3,
        Nationality("ZAF") -> 4,
        Nationality("AUS") -> 5,
      )

      assert(result == expected)
    }

    "Given 5 nationalities when I roll up to 2, I should get back the top 2, and the sum of the rest in other" - {

      val nats = Map(
        Nationality("GBR") -> 1,
        Nationality("ITA") -> 2,
        Nationality("ZWE") -> 3,
        Nationality("ZAF") -> 4,
        Nationality("AUS") -> 5,
      )

      val result = FlightChartComponent.summariseNationalities(nats, 2)

      val expected = Map(
        Nationality("Other") -> 6,
        Nationality("ZAF") -> 4,
        Nationality("AUS") -> 5,
      )

      assert(result == expected)
    }

  }

}
