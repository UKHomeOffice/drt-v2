package services.arrivals

import drt.shared.PortCode
import drt.shared.Terminals.{A1, A2}
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}

case class EdiArrivalsTerminalAdjustments(isRedListed: PortCode => Boolean) extends ArrivalsAdjustmentsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def apply(arrivals: Iterable[Arrival]): Iterable[Arrival] =
    arrivals
      .map { a =>
        val correctedTerminal = if (isRedListed(a.Origin)) A1 else A2
        a.copy(Terminal = correctedTerminal)
      }
}
