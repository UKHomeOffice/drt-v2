package services

import org.specs2.mutable.Specification
import spatutorial.shared.{AirportConfigs, SplitRatio}

class AirportConfigSpec extends Specification{
  "Airport Config" >> {
    "LHR Airport Config" should {

      val splitTotal = AirportConfigs.lhr.defaultPaxSplits.map(_.ratio).sum

      splitTotal must beCloseTo(1, 0.000001)
    }
  }
}
