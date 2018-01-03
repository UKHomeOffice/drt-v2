package services

import org.specs2.mutable.Specification
import drt.shared.{AirportConfigs}

class AirportConfigSpec extends Specification {
  "Airport Config" >> {
    "LHR Airport Config" should {

      val splitTotal = AirportConfigs.lhr.defaultPaxSplits.splits.map(_.ratio).sum

      splitTotal must beCloseTo(1, delta = 0.000001)
    }
  }
}
