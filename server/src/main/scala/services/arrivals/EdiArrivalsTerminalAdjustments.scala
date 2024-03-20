package services.arrivals

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{Arrival, FeedArrival, LiveArrival}
import uk.gov.homeoffice.drt.ports.Terminals.{A1, A2}

object EdiArrivalsTerminalAdjustments extends ArrivalsAdjustmentsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def adjust(arrival: Arrival): Arrival = {
      val a1BaggageBelt = Seq("1", "2", "3").contains(arrival.BaggageReclaimId.getOrElse(""))
      val correctedTerminal = if (a1BaggageBelt) A1 else A2
      arrival.copy(Terminal = correctedTerminal)
  }
}
