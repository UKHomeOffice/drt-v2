package drt.client.services.handlers

import diode.{Action, ActionResult, ModelRW}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.StaffMovementMinute
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.TM
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis

import scala.concurrent.duration._
import scala.language.postfixOps


case class RecordClientSideStaffMovement(terminal: Terminal,
                                         startMinute: MillisSinceEpoch,
                                         endMinute: MillisSinceEpoch,
                                         staff: Int,
                             ) extends Action

class ClientSideStaffMovementsHandler[M](addedStaffMovementMinutes: ModelRW[M, Map[TM, Seq[StaffMovementMinute]]]) extends LoggingActionHandler(addedStaffMovementMinutes) {

  val alertsRequestFrequency: FiniteDuration = 10 seconds

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case RecordClientSideStaffMovement(terminal, start, end, staff) =>
      val newMinutes = (start until end by oneMinuteMillis).map(minute => StaffMovementMinute(terminal, minute, staff, SDate.now().millisSinceEpoch))
      val existingMinutes = value.getOrElse(TM(terminal, start), Seq.empty[StaffMovementMinute])
      updated(value ++ newMinutes.map(m => TM(m.terminal, m.minute) -> (existingMinutes :+ m)).toMap)
  }
}
