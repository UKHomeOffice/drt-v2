package services.arrivals

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{Arrival, FeedArrival}
import uk.gov.homeoffice.drt.ports.PortCode

trait ArrivalsAdjustmentsLike {
  def adjust(arrival: FeedArrival): FeedArrival
}

object ArrivalsAdjustments {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def adjustmentsForPort(portCode: PortCode): Arrival => Arrival =
    if (portCode == PortCode("EDI"))
      EdiArrivalsTerminalAdjustments.adjust
    else {
      ArrivalsAdjustmentsNoop.adjust
    }
}
