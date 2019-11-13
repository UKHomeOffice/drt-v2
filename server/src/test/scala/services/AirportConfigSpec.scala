package services

import drt.shared.airportconfig.Lhr
import org.specs2.mutable.Specification

class AirportConfigSpec extends Specification {
  "Airport Config" >> {
    "LHR Airport Config" should {

      val splitTotal = Lhr.config.terminalPaxSplits("T2").splits.map(_.ratio).sum

      splitTotal must beCloseTo(1, delta = 0.000001)
    }
  }
}
