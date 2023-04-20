package services.arrivals

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.PortCode

trait ArrivalsAdjustmentsLike {
  def apply(arrivals: Iterable[Arrival]): Iterable[Arrival]
}

object ArrivalsAdjustments {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def adjustmentsForPort(portCode: PortCode): ArrivalsAdjustmentsLike =
    if (portCode == PortCode("EDI"))
      EdiArrivalsTerminalAdjustments
    else {
      ArrivalsAdjustmentsNoop
    }
}
