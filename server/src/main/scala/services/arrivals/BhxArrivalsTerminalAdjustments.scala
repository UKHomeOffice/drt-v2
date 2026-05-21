package services.arrivals

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.Terminals.T2

import scala.util.matching.Regex

object BhxArrivalsTerminalAdjustments extends ArrivalsAdjustmentsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  private val gateNumberRegex: Regex = """\d+""".r
  private val t2GateRange = 1 to 25

  private def gateNumber(maybeGate: Option[String]): Option[Int] =
    maybeGate
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(gate => gateNumberRegex.findFirstIn(gate).flatMap(_.toIntOption))

  override def adjust(arrival: Arrival): Arrival = {
    val correctedTerminal = gateNumber(arrival.Gate)
      .collect {
        case gate if t2GateRange.contains(gate) => T2
      }
      .getOrElse(arrival.Terminal)

    if (correctedTerminal != arrival.Terminal)
      log.info(s"[BhxArrivalsTerminalAdjustments][adjust] Correcting BHX terminal from ${arrival.Terminal} to $correctedTerminal for ${arrival.flightCodeString} using gate ${arrival.Gate.getOrElse("unknown")}")

    arrival.copy(Terminal = correctedTerminal)
  }
}

