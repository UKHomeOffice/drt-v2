package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{RetryActionAfter, SetAllShifts}
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.ShiftAssignments
import org.scalajs.dom.XMLHttpRequest
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import upickle.default._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object MinimumStaff {
  implicit val rw: ReadWriter[MinimumStaff] = macroRW
}

case class MinimumStaff(minimumStaff: Int)

case class TerminalMinStaff(terminal: Terminal, minStaff: Option[Int])

case class GetMinStaff(terminalName: String) extends Action

case class SetMinStaff(minStaff: TerminalMinStaff) extends Action

case class SaveMinStaff(minStaff: TerminalMinStaff) extends Action

class MinStaffHandler[M](modelRW: ModelRW[M, Pot[TerminalMinStaff]]) extends LoggingActionHandler(modelRW) {
  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case SetMinStaff(minStaffs) =>
      updated(Ready(minStaffs))

    case GetMinStaff(terminalName) =>
      val apiCallEffect = Effect(DrtApi.get(s"shifts/minimum-staff/${terminalName.toUpperCase}")
        .map((r: XMLHttpRequest) => SetMinStaff(TerminalMinStaff(Terminal(terminalName), Some(read[MinimumStaff](r.responseText).minimumStaff))))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get user feedback. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(SetMinStaff(TerminalMinStaff(Terminal(terminalName), None)))
        })

      effectOnly(apiCallEffect)

    case SaveMinStaff(minStaff) =>
      val apiCallEffect = Effect(DrtApi.post(s"shifts/minimum-staff/${minStaff.terminal.toString}", s"""{"minimumStaff":"${minStaff.minStaff.getOrElse(0)}"}""")
        .map(r => SetAllShifts(read[ShiftAssignments](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to save min staff. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(SaveMinStaff(minStaff), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect + Effect(Future.successful(SetMinStaff(minStaff))))
  }
}
