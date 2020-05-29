package services.arrivals

import controllers.ArrivalGenerator._
import drt.shared.{AclFeedSource, LiveFeedSource, PcpPax}
import org.specs2.mutable.Specification

class PcpPaxSpec extends Specification {

  "When calculating PCP Pax for flights with a Live Feed Source" >> {
    "Given an arrival with 100 pax from API and 50 from Act Pax " +
      "Then I should expect 50 PCP pax" >> {
      val a = arrival(actPax = Option(50), apiPax = Option(100), feedSources = Set(LiveFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 50

      result === expected
    }

    "Given an arrival with None from API and 50 from Act Pax " +
      "Then I should expect 50 PCP pax" >> {
      val a = arrival(actPax = Option(50), apiPax = None, feedSources = Set(LiveFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 50

      result === expected
    }

    "Given an arrival with 100 pax from API and None from Act Pax " +
      "Then I should expect 100 PCP pax" >> {
      val a = arrival(actPax = None, apiPax = Option(100), feedSources = Set(LiveFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 100

      result === expected
    }

    "Given an arrival with 100 pax and None for Transfer " +
      "Then I should expect 100 PCP pax" >> {
      val a = arrival(actPax = Option(100), tranPax = None, feedSources = Set(LiveFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 100

      result === expected
    }

    "Given an arrival with more Transfer Pax than Act Pax and a MaxPax of 150 " +
      "Then we should get 0 PCP Pax " >> {
      val a = arrival(actPax = Option(50), tranPax = Option(100), maxPax = Option(150), feedSources = Set(LiveFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 0

      result === expected
    }

    "Given an arrival with 100 pax and 0 Transfer " +
      "Then I should expect 100 PCP pax" >> {
      val a = arrival(actPax = Option(100), tranPax = Option(0), feedSources = Set(LiveFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 100

      result === expected
    }

    "Given an arrival with 0 act pax, 0 Transfer and 130 Max Pax" +
      "Then I should expect 0 PCP pax" >> {
      val a = arrival(actPax = Option(0), tranPax = Option(0), maxPax = Option(130), feedSources = Set(LiveFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 0

      result === expected
    }

    "Given an arrival with 100 act pax and 10 Transfer" +
      "Then I should expect 90 PCP pax" >> {
      val a = arrival(actPax = Option(100), tranPax = Option(10), feedSources = Set(LiveFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 90

      result === expected
    }

    "Given an arrival with no values set for act pax and transfer and 130 for max pax" +
      "Then I should expect 0 PCP pax" >> {
      val a = arrival(actPax = None, tranPax = None, maxPax = Option(130), feedSources = Set(LiveFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 0

      result === expected
    }
  }

  "When calculating PCP Pax for flights without a Live Feed Source" >> {

    "Given an arrival with 100 pax from API and 50 from Act Pax " +
      "Then I should expect 100 PCP pax - API trumps ACL numbers" >> {
      val a = arrival(actPax = Option(50), apiPax = Option(100), feedSources = Set(AclFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 100

      result === expected
    }

    "Given an arrival with None from API and 50 from Act Pax " +
      "Then I should expect 50 PCP pax" >> {
      val a = arrival(actPax = Option(50), apiPax = None, feedSources = Set(AclFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 50

      result === expected
    }

    "Given an arrival with 100 pax from API and None from Act Pax " +
      "Then I should expect 100 PCP pax" >> {
      val a = arrival(actPax = None, apiPax = Option(100), feedSources = Set(AclFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 100

      result === expected
    }

    "Given an arrival with 100 pax and None for Transfer " +
      "Then I should expect 100 PCP pax" >> {
      val a = arrival(actPax = Option(100), tranPax = None, feedSources = Set(AclFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 100

      result === expected
    }

    "Given an arrival with more Transfer Pax than Act Pax and a MaxPax of 150 " +
      "Then we should get 0 PCP Pax " >> {
      val a = arrival(actPax = Option(50), tranPax = Option(100), maxPax = Option(150), feedSources = Set(AclFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 0

      result === expected
    }

    "Given an arrival with 100 pax and 0 Transfer " +
      "Then I should expect 100 PCP pax" >> {
      val a = arrival(actPax = Option(100), tranPax = Option(0), feedSources = Set(AclFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 100

      result === expected
    }

    "Given an arrival with 0 act pax, 0 Transfer and 130 Max Pax" +
      "Then I should expect 0 PCP pax" >> {
      val a = arrival(actPax = Option(0), tranPax = Option(0), maxPax = Option(130), feedSources = Set(AclFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 0

      result === expected
    }

    "Given an arrival with 100 act pax and 10 Transfer" +
      "Then I should expect 90 PCP pax" >> {
      val a = arrival(actPax = Option(100), tranPax = Option(10), feedSources = Set(AclFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 90

      result === expected
    }

    "Given an arrival with no values set for act pax and transfer and 130 for max pax" +
      "Then I should expect 0 PCP pax" >> {
      val a = arrival(actPax = None, tranPax = None, maxPax = Option(130), feedSources = Set(AclFeedSource))

      val result = PcpPax.bestPaxEstimateWithApi(a)
      val expected = 0

      result === expected
    }



  }

}
