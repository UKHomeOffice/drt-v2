package drt.client.services.handlers

import diode.AnyAction.aType
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW, NoAction}
import drt.client.actions.Actions.SetAllShiftAssignments
import drt.client.logger.log
import drt.client.services.DrtApi
import drt.shared.{Shift, ShiftAssignments}
import uk.gov.homeoffice.drt.time.LocalDate

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class GetShifts(port: String, terminal: String)

case class GetShift(port: String, terminal: String, shiftName: String)

case class SaveShifts(staffShifts: Seq[Shift])

case class RemoveShift(port: String, terminal: String, shiftName: String)

case class SetShifts(staffShifts: Seq[Shift])


class ShiftsHandler[M](modelRW: ModelRW[M, Pot[Seq[Shift]]]) extends LoggingActionHandler(modelRW) {

  import upickle.default.{macroRW, ReadWriter => RW, _}

  implicit val localDateRW: RW[LocalDate] = macroRW
  implicit val staffShiftRW: RW[Shift] = macroRW

  override protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetShifts(port, terminal) =>
      val apiCallEffect = Effect(DrtApi.get(s"shifts/$port/$terminal")
        .map { r =>
          val shifts = read[Seq[Shift]](r.responseText)
          SetShifts(shifts)
        }
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to get shifts: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pot.empty, apiCallEffect)

    case GetShift(port, terminal, shiftName) =>
      val apiCallEffect = Effect(DrtApi.get(s"shifts/$port/$terminal/$shiftName")
        .map(r => SetShifts(read[Seq[Shift]](r.responseText)))
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to get shift: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pot.empty, apiCallEffect)

    case SaveShifts(staffShifts) =>
      val apiCallEffect = Effect(DrtApi.post("shifts/save", write(staffShifts))
        .map(r => SetAllShiftAssignments(read[ShiftAssignments](r.responseText)))
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to save shift: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pot.empty, apiCallEffect)

    case RemoveShift(port, terminal, shiftName) =>
      val apiCallEffect = Effect(DrtApi.delete(s"shifts/remove/$port/$terminal/$shiftName")
        .map(_ => NoAction)
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to remove shift: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pot.empty, apiCallEffect)

    case SetShifts(staffShifts) =>
      updated(Ready(staffShifts))
  }
}
