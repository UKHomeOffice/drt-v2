package services.arrivals

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{FeedArrival, LiveArrival}
import uk.gov.homeoffice.drt.ports.Terminals.{A1, A2}

object EdiArrivalsTerminalAdjustments extends ArrivalsAdjustmentsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def adjust(arrival: FeedArrival): FeedArrival = arrival match {
    case liveArrival: LiveArrival =>
      val a1BaggageBelt = Seq("1", "2", "3").contains(liveArrival.baggageReclaim.getOrElse(""))
      val correctedTerminal = if (a1BaggageBelt) A1 else A2
      liveArrival.copy(terminal = correctedTerminal)
    case _ => arrival
  }
}
