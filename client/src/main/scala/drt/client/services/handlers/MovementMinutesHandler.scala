package drt.client.services.handlers

import diode.{Action, ActionResult, ModelRW}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.MovementMinute
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.TM
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps


case class AddMovementMinutes(terminal: Terminal,
                              startMinute: MillisSinceEpoch,
                              endMinute: MillisSinceEpoch,
                              staff: Int,
                             ) extends Action

class MovementMinutesHandler[M](movementMinutes: ModelRW[M, Map[TM, Seq[MovementMinute]]]) extends LoggingActionHandler(movementMinutes) {

  val alertsRequestFrequency: FiniteDuration = 10 seconds

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case AddMovementMinutes(terminal, start, end, staff) =>
      val newMinutes = (start until end by oneMinuteMillis).map(minute => MovementMinute(terminal, minute, staff, SDate.now().millisSinceEpoch))
      val existingMinutes = value.getOrElse(TM(terminal, start), Seq.empty[MovementMinute])
      updated(value ++ newMinutes.map(m => TM(m.terminal, m.minute) -> (existingMinutes :+ m)).toMap)
  }
}
