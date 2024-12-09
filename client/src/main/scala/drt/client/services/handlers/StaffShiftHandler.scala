package drt.client.services.handlers

import diode.{ActionResult, Effect, ModelRW, NoAction}
import diode.data.{Pot, Ready}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.DrtApi
import drt.shared.{ShiftAssignments, StaffShift}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import upickle.default._

case class GetShifts(port: String, terminal: String)
case class GetShift(port: String, terminal: String, shiftName: String)
case class SaveShift(staffShifts: Seq[StaffShift])
case class RemoveShift(port: String, terminal: String, shiftName: String)

class StaffShiftHandler[M](modelRW: ModelRW[M, Pot[Seq[StaffShift]]]) extends LoggingActionHandler(modelRW) {
  import upickle.default.{macroRW, ReadWriter => RW}
  implicit val rw: RW[StaffShift] = macroRW

  override protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetShifts(port, terminal) =>
      val apiCallEffect = Effect(DrtApi.get(s"staff-shifts/$port/$terminal")
        .map(r => SetAllShifts(read[ShiftAssignments](r.responseText)))
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to get shifts: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pot.empty, apiCallEffect)

    case GetShift(port, terminal, shiftName) =>
      val apiCallEffect = Effect(DrtApi.get(s"staff-shifts/$port/$terminal/$shiftName")
        .map(r => SetAllShifts(read[ShiftAssignments](r.responseText)))
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to get shift: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pot.empty, apiCallEffect)

    case SaveShift(staffShifts) =>
      val apiCallEffect = Effect(DrtApi.post("staff-shifts/save", write(staffShifts))
        .map(_ => NoAction)
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to save shift: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pot.empty, apiCallEffect)

    case RemoveShift(port, terminal, shiftName) =>
      val apiCallEffect = Effect(DrtApi.delete(s"staff-shifts/remove/$port/$terminal/$shiftName")
        .map(_ => NoAction)
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to remove shift: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pot.empty, apiCallEffect)
  }
}