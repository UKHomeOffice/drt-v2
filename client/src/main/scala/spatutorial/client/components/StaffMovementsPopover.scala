package spatutorial.client.components

import japgolly.scalajs.react.{ReactComponentB, _}
import japgolly.scalajs.react.vdom.ReactTagOf
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom.html
import org.scalajs.dom.html.Div
import spatutorial.client.SPAMain.{Loc, TerminalDepsLoc}
import spatutorial.client.logger._
import spatutorial.client.modules.PopoverWrapper
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.TerminalName
import spatutorial.shared.SDateLike
import spatutorial.client.actions.Actions.{AddStaffMovement, SaveStaffMovements}

import scala.util.{Failure, Success}
import scala.collection.immutable.Seq

object StaffMovementsPopover {

  case class StaffMovementPopoverState(
                                        hovered: Boolean = false,
                                        reason: String = "",
                                        terminalName: TerminalName,
                                        date: String = "",
                                        startTimeHours: Int = 0,
                                        startTimeMinutes: Int = 0,
                                        endTimeHours: Int = 0,
                                        endTimeMinutes: Int = 0,
                                        numberOfStaff: Int = 1
                                      )

  def roundToNearest(nearest: Int)(x: Int): Int = {
    (x.toDouble / nearest).round.toInt * nearest
  }

  def defaultTerminal(terminalNames: Seq[TerminalName], page: Loc): TerminalName = page match {
    case p: TerminalDepsLoc => p.id
    case _ => terminalNames.headOption.getOrElse("")
  }

  def apply(terminalNames: Seq[TerminalName], page: Loc, trigger: String, reason: String, startDate: SDateLike, endDate: SDateLike, bottom: String) = ReactComponentB[Unit]("staffMovementPopover")
    .initialState_P((p) => {
      StaffMovementPopoverState(
        reason = reason,
        terminalName = defaultTerminal(terminalNames, page),
        date = f"${startDate.getDate()}%02d/${startDate.getMonth()}%02d/${startDate.getFullYear - 2000}%02d",
        startTimeHours = startDate.getHours(),
        startTimeMinutes = roundToNearest(5)(startDate.getMinutes()),
        endTimeHours = endDate.getHours(),
        endTimeMinutes = roundToNearest(5)(endDate.getMinutes()))
    }).renderS((scope, state) => {

    def selectTerminal(defaultValue: String) = {
      <.select(
        ^.defaultValue := defaultValue,
        ^.onChange ==> ((e: ReactEventI) => {
          val newValue: String = e.target.value
          scope.modState(_.copy(terminalName = newValue))
        }),
        terminalNames.map(x => <.option(^.value := x, x)))
    }

    def selectFromRange(range: Range, defaultValue: Int, callback: (String) => (StaffMovementPopoverState) => StaffMovementPopoverState, applyRounding: Int => Int) = {
      <.select(
        ^.defaultValue := applyRounding(defaultValue),
        ^.onChange ==> ((e: ReactEventI) => {
          val newValue: String = e.target.value
          scope.modState(callback(newValue))
        }),
        range.map(x => <.option(^.value := x, f"$x%02d")))
    }

    def trySaveMovement = (e: ReactEventI) => {
      val startTime: String = f"${state.startTimeHours}%02d:${state.startTimeMinutes}%02d"
      val endTime: String = f"${state.endTimeHours}%02d:${state.endTimeMinutes}%02d"
      val numberOfStaff: String = s"-${state.numberOfStaff.toString}"
      val shiftTry = Shift(state.reason, state.terminalName, state.date, startTime, endTime, numberOfStaff)
      shiftTry match {
        case Success(shift) =>
          for (movement <- StaffMovements.shiftsToMovements(Seq(shift))) yield {
            SPACircuit.dispatch(AddStaffMovement(movement))
            log.info(s"Dispatched AddStaffMovement($movement")
          }
          SPACircuit.dispatch(SaveStaffMovements())
          scope.modState(_.copy(hovered = false))
        case Failure(error) =>
          log.info("Invalid shift")
          scope.modState(_.copy(hovered = true))
      }
    }

    val popover = <.div(
      ^.onMouseEnter ==> ((e: ReactEvent) => scope.modState(_.copy(hovered = true))),
      if (state.hovered) {
        PopoverWrapper(trigger = trigger, className = "staff-movement-popover", position = bottom)({
          def labelledInput(labelText: String, value: String, callback: (String) => (StaffMovementPopoverState) => StaffMovementPopoverState): ReactTagOf[html.Div] = {
            popoverFormRow(labelText, <.input.text(^.value := value, ^.onChange ==> ((e: ReactEventI) => {
              val newValue: String = e.target.value
              scope.modState(callback(newValue))
            })))
          }

          def timeSelector(label: String,
                           hourDefault: Int,
                           minuteDefault: Int,
                           hourCallback: (String) => (StaffMovementPopoverState) => StaffMovementPopoverState,
                           minuteCallback: (String) => (StaffMovementPopoverState) => StaffMovementPopoverState
                          ): ReactTagOf[Div] = {
            popoverFormRow(label,
              selectFromRange(
                0 to 23, hourDefault, hourCallback, (x) => x
              ), ":",
              selectFromRange(
                0 to 59 by 5, minuteDefault, minuteCallback, roundToNearest(5)
              )
            )
          }

          def popoverFormRow(label: String, xs: TagMod*) = {
            <.div(^.className := "form-group row",
              <.label(label, ^.className := "col-sm-2 col-form-label"),
              <.div(
                ^.className := "col-sm-10",
                xs))
          }

          <.div(^.className := "container", ^.key := "IS81",
            popoverFormRow("Terminal", selectTerminal(defaultTerminal(terminalNames, page))),
            labelledInput("Reason", state.reason, (v: String) => (s: StaffMovementPopoverState) => s.copy(reason = v)),
            labelledInput("Date", state.date, (v: String) => (s: StaffMovementPopoverState) => s.copy(date = v)),
            timeSelector("Start time", state.startTimeHours, state.startTimeMinutes,
              (v: String) => (s: StaffMovementPopoverState) => s.copy(startTimeHours = v.toInt),
              (v: String) => (s: StaffMovementPopoverState) => s.copy(startTimeMinutes = v.toInt)),
            timeSelector("End time", state.endTimeHours, state.endTimeMinutes,
              (v: String) => (s: StaffMovementPopoverState) => s.copy(endTimeHours = v.toInt),
              (v: String) => (s: StaffMovementPopoverState) => s.copy(endTimeMinutes = v.toInt)),
            popoverFormRow("Number of staff", <.input.number(^.value := state.numberOfStaff.toString, ^.onChange ==> ((e: ReactEventI) => {
              val newValue: String = e.target.value
              scope.modState((s: StaffMovementPopoverState) => s.copy(numberOfStaff = newValue.toInt))
            }))),

            <.div(^.className := "form-group-row",
              <.div(^.className := "col-sm-2"),
              <.div(^.className := "offset-sm-2 col-sm-10 btn-toolbar",
                <.button("Save", ^.className := "btn btn-primary", ^.onClick ==> trySaveMovement),
                <.button("Cancel", ^.className := "btn btn-default", ^.onClick ==> ((e: ReactEventI) => {
                  scope.modState(_.copy(hovered = false))
                }))
              )
            )
          )
        })
      } else {
        <.div(^.className := "popover-trigger", trigger)
      })
    popover
  }).build
}
