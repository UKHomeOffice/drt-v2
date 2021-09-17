package services.arrivals

import drt.shared.PortCode
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import services.AirportToCountry
import uk.gov.homeoffice.drt.redlist.RedListUpdates

trait ArrivalsAdjustmentsLike {
  def apply(arrivals: Iterable[Arrival], redListUpdates: RedListUpdates): Iterable[Arrival]
}

object ArrivalsAdjustments {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def adjustmentsForPort(portCode: PortCode): ArrivalsAdjustmentsLike =
    if (portCode == PortCode("EDI")) {
      EdiArrivalsTerminalAdjustments((pc, date, rlu) => AirportToCountry.isRedListed(pc, date, rlu))
    }
    else {
      log.info(s"Using ArrivalsAdjustmentsNoop")
      ArrivalsAdjustmentsNoop
    }
}
