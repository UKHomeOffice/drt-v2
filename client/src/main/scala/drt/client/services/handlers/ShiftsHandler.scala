package drt.client.services.handlers

import diode.AnyAction.aType
import diode.data.{Empty, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW, NoAction}
import drt.client.actions.Actions.SetAllShiftAssignments
import drt.client.logger.log
import drt.client.services.DrtApi
import drt.shared.ShiftAssignments
import uk.gov.homeoffice.drt.Shift
import upickle.default._

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class GetShifts(terminal: String, viewDate: Option[String] = None, dayRange: Option[String] = None)

case class SaveShifts(staffShifts: Seq[Shift])

case class UpdateShift(shift: Option[Shift], shiftName: String)

case class RemoveShift(shift: Option[Shift], shiftName: String)

case class SetShifts(staffShifts: Seq[Shift])


class ShiftsHandler[M](modelRW: ModelRW[M, Pot[Seq[Shift]]]) extends LoggingActionHandler(modelRW) {

  override protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetShifts(terminal, dateOption, dayRangeOption) =>
      val apiCallEffect = getShiftsFromServer(terminal, dateOption, dayRangeOption)
      updated(Empty, apiCallEffect)

    case SaveShifts(staffShifts) =>
      val apiCallEffect = Effect(DrtApi.post("shifts", write(staffShifts))
        .map { r =>
          val assignments = read[ShiftAssignments](r.responseText)
          log.info(s"Received shift assignments after saving shifts")
          SetAllShiftAssignments(assignments)
        }.recover {
          case t =>
            log.error(msg = s"Failed to save shift: ${t.getMessage}")
            NoAction
        }
      )
      updated(Pot.empty, apiCallEffect)

    case UpdateShift(shift, shiftName) =>
      shift match {
        case Some(s) =>
          val apiCallEffect = Effect(DrtApi.put(s"shifts/$shiftName", write(s))
            .map(r => SetAllShiftAssignments(read[ShiftAssignments](r.responseText)))
            .recoverWith {
              case t =>
                log.error(msg = s"Failed to update shift: ${t.getMessage}")
                Future(NoAction)
            })
          updated(Pot.empty, apiCallEffect)
        case None =>
          noChange
      }

    case RemoveShift(shift, shiftName) =>
      shift match {
        case Some(s) =>
          val apiCallEffect = Effect(DrtApi.delete(s"shifts/${s.terminal}/$shiftName/${s.startDate}/${s.startTime}")
            .map(r => SetAllShiftAssignments(read[ShiftAssignments](r.responseText)))
            .recoverWith {
              case t =>
                log.error(msg = s"Failed to delete shift: ${t.getMessage}")
                Future(NoAction)
            })
          updated(Pot.empty, apiCallEffect)
        case None =>
          noChange
      }

    case SetShifts(staffShifts) =>
      updated(Ready(staffShifts))
  }

  private def getShiftsFromServer(terminal: String, dateOption: Option[String], dayRangeOption: Option[String]) = {
    val dayRange = dayRangeOption.getOrElse("monthly")
    val url: String = shiftsUrl(terminal, dateOption, dayRange)
    val apiCallEffect = Effect(DrtApi.get(url).map { r =>
      log.info(msg = s"Received shifts for $terminal")
      val arr = ujson.read(r.responseText).arr
      arr.zipWithIndex.foreach { case (el, idx) =>
        try {
          read[Shift](el)
        } catch {
          case e: Exception =>
            log.error(s"Failed to parse shift at index $idx: ${el.render()}. Error: ${e.getMessage}")
        }
      }
      val shifts = read[Seq[Shift]](r.responseText)
      SetShifts(shifts)
    }.recoverWith {
      case t =>
        log.error(msg = s"Failed to get shifts: ${t.getMessage}")
        Future(NoAction)
    })
    apiCallEffect
  }

  private def shiftsUrl(terminal: String, dateOption: Option[String], dayRange: String) = {
    val url = dateOption match {
      case Some(date) =>
        s"shifts/$terminal/$dayRange/$date"
      case None =>
        s"shifts/$terminal/$dayRange"
    }
    url
  }
}
