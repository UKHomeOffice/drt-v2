package services

import drt.shared.{AirportInfo, PortCode}
import org.specs2.mutable.SpecificationLike

object AirportToCountryTests extends SpecificationLike {
  "can load csv" >> {
    val result = AirportToCountry.airportInfoByIataPortCode.get("GKA")
    val expected = Some(AirportInfo("Goroka", "Goroka", "Papua New Guinea", "GKA"))
    result === expected
  }

  "Given a PortCode in a red list country" >> {
    "AirportToCountry should tell me it's a red list port" >> {
      val bulawayoAirport = PortCode("BUQ")
      AirportToCountry.isRedListed(bulawayoAirport) === true
    }
  }
}

