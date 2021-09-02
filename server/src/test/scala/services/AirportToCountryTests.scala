package services

import drt.shared.redlist.{RedListUpdate, RedListUpdates}
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
      val updates = RedListUpdates(Map(0L -> RedListUpdate(0L, Map("Zimbabwe" -> "ZWE"), List())))
      AirportToCountry.isRedListed(bulawayoAirport, SDate("2021-08-01T00:00").millisSinceEpoch, updates) === true
    }
  }
}

