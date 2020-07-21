package services.arrivals

import drt.shared.PortCode
import drt.shared.Terminals.Terminal
import org.specs2.mutable.Specification

class ArrivalsAdjustmentsSpec extends Specification {

  "Given a non-EDI port code and no CSV url then I should get back an ArrivalsAdjustmentsNoop" >> {
    val result = ArrivalsAdjustments.adjustmentsForPort(PortCode("LHR"), None)
    val expected = ArrivalsAdjustmentsNoop

    result === expected
  }

  "Given EDI as the port code and no CSV url then I should get back an EdiArrivalsTerminalAdjustments with empty map" >> {
    val result = ArrivalsAdjustments.adjustmentsForPort(PortCode("EDI"), None)
    val expected = EdiArrivalsTerminalAdjustments(Map())

    result === expected
  }

  "Given EDI as the port code and bad CSV url then I should get back an EdiArrivalsTerminalAdjustments with empty map" >> {
    val result = ArrivalsAdjustments.adjustmentsForPort(PortCode("EDI"), Option("file://invalid"))
    val expected = EdiArrivalsTerminalAdjustments(Map())

    result === expected
  }

  "Given EDI as the port code and a correct CSV url then I should get back an EdiArrivalsTerminalAdjustments with historic map" >> {
    val url = getClass.getClassLoader.getResource("edi-terminal-map-fixture.csv")
    val result = ArrivalsAdjustments.adjustmentsForPort(PortCode("EDI"), Option(url.toString))
    val expected = EdiArrivalsTerminalAdjustments(Map(
      "TST0100" -> Map(
        "January" -> Terminal("A1"),
        "February" -> Terminal("A1"),
        "March" -> Terminal("A1"),
        "April" -> Terminal("A1"),
        "May" -> Terminal("A1"),
        "June" -> Terminal("A1"),
        "July" -> Terminal("A1"),
        "August" -> Terminal("A1"),
        "September" -> Terminal("A1"),
        "October" -> Terminal("A1"),
        "November" -> Terminal("A1"),
        "December" -> Terminal("A1")
      )
    ))

    result === expected
  }

}
