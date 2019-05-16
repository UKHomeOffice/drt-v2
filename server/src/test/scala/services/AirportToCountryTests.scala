package services

import drt.shared.AirportInfo
import utest._

import scala.concurrent.ExecutionContext.Implicits.global

object AirportToCountryTests extends TestSuite {
  def tests = TestSuite {
    "can load csv" - {
      val result = AirportToCountry.airportInfo.get("GKA")
      val expected = Some(AirportInfo("Goroka", "Goroka", "Papua New Guinea", "GKA"))
      assert(result == expected)
    }
  }
}

