package services.arrivals

import drt.shared.{ArrivalsDiff, UniqueArrival}
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

case class EdiArrivalsTerminalAdjustments(historicFlightTerminalMap: Map[String, Map[String, Terminal]])
  extends ArrivalsAdjustmentsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val defaultTerminal: Terminal = Terminal("A1")

  override def apply(arrivalsDiff: ArrivalsDiff, arrivalsKeys: Iterable[UniqueArrival]): ArrivalsDiff = {
    log.info(s"Adjusting terminals ${arrivalsDiff.toUpdate.size} updates and ${arrivalsDiff.toRemove.size} deletions")
    val updatedDiff = applyBaggageReclaimIdRule(
      applyHistoricTerminalRule(arrivalsDiff)
    )

    val arrivalsBeforeUpdates: Set[UniqueArrival] = arrivalsKeys.toSet -- updatedDiff.toUpdate.keys.toSet
    val arrivalsThatHaveMovedTerminals = arrivalsBeforeUpdates.flatMap(ua => arrivalsDiff.toUpdate.get(ua))

    updatedDiff.copy(toRemove = updatedDiff.toRemove ++ arrivalsThatHaveMovedTerminals)
  }

  def applyHistoricTerminalRule(arrivalsDiff: ArrivalsDiff): ArrivalsDiff = arrivalsDiff
    .copy(
      toUpdate = arrivalsDiff.toUpdate.map {
        case (_, a) =>
          val adjustedArrival: Arrival = withHistoricTerminal(a)
          adjustedArrival.unique -> adjustedArrival
      },
      toRemove = arrivalsDiff.toRemove.map(withHistoricTerminal)
    )

  def withHistoricTerminal(a: Arrival): Arrival = {
    val adjustedTerminal: Terminal = historicFlightTerminalMap
      .get(a.flightCodeString)
      .flatMap(_.get(SDate(a.Scheduled).getMonthString())).getOrElse(defaultTerminal)
    a.copy(Terminal = adjustedTerminal)
  }

  def applyBaggageReclaimIdRule(arrivalsDiff: ArrivalsDiff): ArrivalsDiff = {
    arrivalsDiff
      .copy(
        toUpdate = arrivalsDiff.toUpdate.map {
          case (_, a) if a.BaggageReclaimId.contains("7") =>
            val withAdjustedTerminal = a.copy(Terminal = Terminal("A2"))
            withAdjustedTerminal.unique -> withAdjustedTerminal
          case (_, a) if a.BaggageReclaimId.isDefined =>
            val withAdjustedTerminal = a.copy(Terminal = Terminal("A1"))
            withAdjustedTerminal.unique -> withAdjustedTerminal
          case useHistoric => useHistoric
        },
        toRemove = arrivalsDiff.toRemove.map {
          case a if a.BaggageReclaimId.contains("7") => a.copy(Terminal = Terminal("A2"))
          case a if a.BaggageReclaimId.isDefined => a.copy(Terminal = Terminal("A1"))
          case other => other
        }
      )
  }
}
