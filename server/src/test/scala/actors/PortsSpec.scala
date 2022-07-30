package actors

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.PortCode

class PortsSpec extends Specification {
  "Given a port" >> {
    "It should know if it's domestic" >> {
      val abb = PortCode("ABB")
      abb.isDomestic === true
    }

    "It should know if it's not domestic" >> {
      val abb = PortCode("IOM")
      abb.isDomestic === false
    }

    "It should know if it's in the CTA" >> {
      val abb = PortCode("IOM")
      abb.isCta === true
    }

    "It should know if it's not in the CTA" >> {
      val abb = PortCode("ABB")
      abb.isCta === false
    }

    "It should know if it's not in the CTA" >> {
      val abb = PortCode("ABB")
      abb.isCta === false
    }
  }
}
