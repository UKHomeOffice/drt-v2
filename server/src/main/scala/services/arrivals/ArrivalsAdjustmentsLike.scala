package services.arrivals

import drt.shared.PortCode
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import services.AirportToCountry

trait ArrivalsAdjustmentsLike {
  def apply(arrivals: Iterable[Arrival]): Iterable[Arrival]
}

object ArrivalsAdjustments {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def adjustmentsForPort(portCode: PortCode): ArrivalsAdjustmentsLike =
    if (portCode == PortCode("EDI")) {
      EdiArrivalsTerminalAdjustments(AirportToCountry.isRedListed)
    }
    else {
      log.info(s"Using ArrivalsAdjustmentsNoop")
      ArrivalsAdjustmentsNoop
    }
}
