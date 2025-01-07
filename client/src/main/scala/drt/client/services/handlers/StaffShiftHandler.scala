package drt.client.services.handlers

import diode.AnyAction.aType
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW, NoAction}
import drt.client.logger.log
import drt.client.services.DrtApi
import drt.shared.StaffShift
import uk.gov.homeoffice.drt.time.LocalDate
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class GetShifts(port: String, terminal: String)

case class GetShift(port: String, terminal: String, shiftName: String)

case class SaveShift(staffShifts: Seq[StaffShift])

case class RemoveShift(port: String, terminal: String, shiftName: String)

case class SetShifts(staffShifts: Seq[StaffShift])


class StaffShiftHandler[M](modelRW: ModelRW[M, Pot[Seq[StaffShift]]]) extends LoggingActionHandler(modelRW) {

  import upickle.default.{macroRW, ReadWriter => RW}
  import upickle.default._

  implicit val localDateRW: RW[LocalDate] = macroRW
  implicit val staffShiftRW: RW[StaffShift] = macroRW

  override protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetShifts(port, terminal) =>
      val apiCallEffect = Effect(DrtApi.get(s"default-staff-shifts/$port/$terminal")
        .map { r =>
          val shifts = read[Seq[StaffShift]](r.responseText)
          SetShifts(shifts)
        }
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to get shifts: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pot.empty, apiCallEffect)

    case GetShift(port, terminal, shiftName) =>
      val apiCallEffect = Effect(DrtApi.get(s"default-staff-shift/$port/$terminal/$shiftName")
        .map(r => SetShifts(read[Seq[StaffShift]](r.responseText)))
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to get shift: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pot.empty, apiCallEffect)

    case SaveShift(staffShifts) =>
      val apiCallEffect = Effect(DrtApi.post("default-staff-shifts/save", write(staffShifts))
        .map(_ => NoAction)
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to save shift: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pot.empty, apiCallEffect)

    case RemoveShift(port, terminal, shiftName) =>
      val apiCallEffect = Effect(DrtApi.delete(s"default-staff-shifts/remove/$port/$terminal/$shiftName")
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