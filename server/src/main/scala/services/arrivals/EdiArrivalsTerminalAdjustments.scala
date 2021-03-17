package services.arrivals

import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared.{ArrivalsDiff, UniqueArrivalWithOrigin}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

case class EdiArrivalsTerminalAdjustments(historicFlightTerminalMap: Map[String, Map[String, Terminal]])
  extends ArrivalsAdjustmentsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val defaultTerminal: Terminal = Terminal("A1")

  override def apply(originalArrivalsDiff: ArrivalsDiff, allArrivalKeys: Iterable[UniqueArrivalWithOrigin]): ArrivalsDiff = {
    log.info(s"Adjusting terminals ${originalArrivalsDiff.toUpdate.size} updates and ${originalArrivalsDiff.toRemove.size} deletions")
    val arrivalsDiffWithTerminalUpdates = applyBaggageReclaimIdRule(
      applyHistoricTerminalRule(originalArrivalsDiff)
    )

    val arrivalsWithoutTerminalUpdates: Set[UniqueArrivalWithOrigin] = allArrivalKeys.toSet -- arrivalsDiffWithTerminalUpdates.toUpdate.keys.toSet
    val arrivalsThatHaveMovedTerminals = arrivalsWithoutTerminalUpdates.flatMap(ua => originalArrivalsDiff.toUpdate.get(ua))

    arrivalsDiffWithTerminalUpdates.copy(toRemove = arrivalsDiffWithTerminalUpdates.toRemove ++ arrivalsThatHaveMovedTerminals)
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
