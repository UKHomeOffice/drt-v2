package drt.client.services.handlers

import diode.AnyAction.aType
import diode.{ActionResult, Effect, ModelRW, NoAction}
import diode.data.{Pot, Ready}
import drt.client.logger.log
import drt.client.services.DrtApi
import drt.shared.{ShiftAssignments, ShiftSummaryData}
import uk.gov.homeoffice.drt.time.LocalDate
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future

case class GetShiftSummaryStaffing(port: String,
                                   terminal: String,
                                   viewDate: LocalDate,
                                   interval: Int,
                                   dayRange: String)

case class UpdateShiftSummaryStaffingWithAssignment(shiftAssignments: ShiftAssignments,
                                                    port: String,
                                                    terminal: String,
                                                    viewDate: LocalDate,
                                                    interval: Int,
                                                    dayRange: String)

case class SetShiftSummaryStaffing(staffShifts: Seq[ShiftSummaryData.ShiftSummaryStaffing])


class ShiftSummaryStaffingHandler[M](modelRW: ModelRW[M, Pot[Seq[ShiftSummaryData.ShiftSummaryStaffing]]]) extends LoggingActionHandler(modelRW) {

  import upickle.default.{macroRW, ReadWriter => RW}
  import upickle.default._

  implicit val localDateRW: RW[LocalDate] = macroRW
  implicit val shiftDateRW: RW[ShiftSummaryData.ShiftDate] = macroRW
  implicit val shiftSummaryRW: RW[ShiftSummaryData.ShiftSummary] = macroRW
  implicit val staffShiftRW: RW[ShiftSummaryData.ShiftSummaryStaffing] = macroRW
  implicit val staffTableEntryRW: RW[ShiftSummaryData.StaffTableEntry] = macroRW


  override protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetShiftSummaryStaffing(port, terminal, viewDate, interval, dayRange) =>
      val apiCallEffect = Effect(DrtApi.get(s"shifts/staff-assignments/$port/$terminal/$viewDate/$interval/$dayRange")
        .map { r =>
          val shifts = read[Seq[ShiftSummaryData.ShiftSummaryStaffing]](r.responseText)
          SetShiftSummaryStaffing(shifts)
        }
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to get shifts: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pot.empty, apiCallEffect)

    case SetShiftSummaryStaffing(staffShifts) =>
      updated(Ready(staffShifts))

    case UpdateShiftSummaryStaffingWithAssignment(shiftAssignments, port, terminal, viewDate, interval, dayRange) =>
      val apiCallEffect = Effect(DrtApi.post(s"shifts/staff-assignments/$port/$terminal/$viewDate/$interval/$dayRange", write(shiftAssignments))
        .map { r =>
          val shifts = read[Seq[ShiftSummaryData.ShiftSummaryStaffing]](r.responseText)
          SetShiftSummaryStaffing(shifts)
        }
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to get shifts: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pot.empty, apiCallEffect)
  }

}