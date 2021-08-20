package services.arrivals

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.PortCode
import drt.shared.Terminals.{A1, A2}
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}

case class EdiArrivalsTerminalAdjustments(isRedListed: (PortCode, MillisSinceEpoch) => Boolean) extends ArrivalsAdjustmentsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def apply(arrivals: Iterable[Arrival]): Iterable[Arrival] =
    arrivals
      .map { arrival =>
        val correctedTerminal = if (isRedListed(arrival.Origin, arrival.Scheduled)) A1 else A2
        arrival.copy(Terminal = correctedTerminal)
      }
}
