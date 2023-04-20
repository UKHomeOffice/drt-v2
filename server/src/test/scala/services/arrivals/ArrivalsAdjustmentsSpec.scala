package services.arrivals

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.PortCode

class ArrivalsAdjustmentsSpec extends Specification {
  "Given a non-EDI port code then I should get back an ArrivalsAdjustmentsNoop" >> {
    val result = ArrivalsAdjustments.adjustmentsForPort(PortCode("LHR"))
    result === ArrivalsAdjustmentsNoop
  }

  "Given EDI as the port code then I should get back an EdiArrivalsTerminalAdjustments" >> {
    val result = ArrivalsAdjustments.adjustmentsForPort(PortCode("EDI"))
    result === EdiArrivalsTerminalAdjustments
  }
}
