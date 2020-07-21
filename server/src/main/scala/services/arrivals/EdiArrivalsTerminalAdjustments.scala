package services.arrivals

import drt.shared.ArrivalsDiff
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import services.SDate

case class EdiArrivalsTerminalAdjustments(historicFlightTerminalMap: Map[String, Map[String, Terminal]])
  extends ArrivalsAdjustmentsLike {

  val defaultTerminal = Terminal("A1")

  override def apply(arrivalsDiff: ArrivalsDiff): ArrivalsDiff = applyBaggageReclaimIdRule(
    applyHistoricTerminalRule(arrivalsDiff)
  )

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

  def applyBaggageReclaimIdRule(arrivalsDiff: ArrivalsDiff) = {
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
