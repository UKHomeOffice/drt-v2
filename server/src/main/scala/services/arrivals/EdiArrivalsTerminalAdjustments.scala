package services.arrivals

import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.{A1, A2}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

case class EdiArrivalsTerminalAdjustments(isRedListed: (PortCode, MillisSinceEpoch, RedListUpdates) => Boolean) extends ArrivalsAdjustmentsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def apply(arrivals: Iterable[Arrival], redListUpdates: RedListUpdates): Iterable[Arrival] =
    arrivals
      .map { arrival =>
        val redListed = isRedListed(arrival.Origin, arrival.Scheduled, redListUpdates)
        val correctedTerminal = if (redListed) A1 else A2
        arrival.copy(Terminal = correctedTerminal)
      }
}
