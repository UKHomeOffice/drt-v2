package services.arrivals

import controllers.ArrivalGenerator._
import drt.shared.PcpPax
import org.specs2.mutable.Specification

class PcpPaxSpec extends Specification {

  "Given an arrival with 100 pax from API and 50 from Act Pax for Transfer " +
    "Then I should expect 100 PCP pax" >> {
    val a = arrival(actPax = Option(50), apiPax = Option(100))

    val result = PcpPax.bestPax(a)
    val expected = 100

    result === expected
  }

  "Given an arrival with 100 pax and None for Transfer " +
    "Then I should expect 100 PCP pax" >> {
    val a = arrival(actPax = Option(100), tranPax = None)

    val result = PcpPax.bestPax(a)
    val expected = 100

    result === expected
  }

  "Given an arrival with more Transfer Pax than Act Pax and a MaxPax of 150 then we should get 150 PCP Pax " +
    "Then I should expect 150 PCP pax" >> {
    val a = arrival(actPax = Option(50), tranPax = Option(100), maxPax = Option(150))

    val result = PcpPax.bestPax(a)
    val expected = 150

    result === expected
  }

  "Given an arrival with 100 pax and 0 Transfer " +
    "Then I should expect 100 PCP pax" >> {
    val a = arrival(actPax = Option(100), tranPax = Option(0))

    val result = PcpPax.bestPax(a)
    val expected = 100

    result === expected
  }

  "Given an arrival with 0 act pax, 0 Transfer and 130 Max Pax" +
    "Then I should expect 0 PCP pax" >> {
    val a = arrival(actPax = Option(0), tranPax = Option(0), maxPax = Option(130))

    val result = PcpPax.bestPax(a)
    val expected = 0

    result === expected
  }

  "Given an arrival with no values set for act pax and transfer and 130 for max pax" +
    "Then I should expect 130 PCP pax" >> {
    val a = arrival(actPax = None, tranPax = None, maxPax = Option(130))

    val result = PcpPax.bestPax(a)
    val expected = 130

    result === expected
  }

  "Given an arrival with no values set for act pax and transfer and 130 for max pax" +
    "Then I should expect 130 PCP pax" >> {
    val a = arrival(actPax = None, tranPax = None, maxPax = Option(130))

    val result = PcpPax.bestPax(a)
    val expected = 130

    result === expected
  }

}
