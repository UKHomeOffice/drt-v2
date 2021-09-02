package services.arrivals

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.PortCode
import drt.shared.Terminals.{A1, A2}
import drt.shared.api.Arrival
import drt.shared.redlist.RedListUpdates
import org.slf4j.{Logger, LoggerFactory}

case class EdiArrivalsTerminalAdjustments(isRedListed: (PortCode, MillisSinceEpoch, RedListUpdates) => Boolean) extends ArrivalsAdjustmentsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def apply(arrivals: Iterable[Arrival], redListUpdates: RedListUpdates): Iterable[Arrival] =
    arrivals
      .map { arrival =>
        redListUpdates
        val correctedTerminal = if (isRedListed(arrival.Origin, arrival.Scheduled, redListUpdates)) A1 else A2
        arrival.copy(Terminal = correctedTerminal)
      }
}
