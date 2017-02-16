package spatutorial.client.components

import japgolly.scalajs.react.{ReactComponentB, _}
import japgolly.scalajs.react.vdom.ReactTagOf
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom.html
import org.scalajs.dom.html.{Div, Select}
import spatutorial.client.logger._
import spatutorial.client.services._
import spatutorial.shared.SDate

import scala.util.{Failure, Success}
import scala.collection.immutable.Seq

object StaffMovementsPopover {

  case class StaffMovementPopoverState(
                                        hovered: Boolean = false,
                                        reason: String = "",
                                        date: String = "",
                                        startTimeHours: Int = 0,
                                        startTimeMinutes: Int = 0,
                                        endTimeHours: Int = 0,
                                        endTimeMinutes: Int = 0,
                                        numberOfStaff: Int = 1
                                      )

  def apply(trigger: String, reason: String, startDate: SDate, endDate: SDate, bottom: String) = ReactComponentB[Unit]("staffMovementPopover")
    .initialState_P((p) => {
      StaffMovementPopoverState(
        reason = reason,
        date = f"${startDate.getDate}%02d/${startDate.getMonth}%02d/${startDate.getFullYear - 2000}%02d",
        startTimeHours = startDate.getHours(),
        startTimeMinutes = startDate.getMinutes(),
        endTimeHours = endDate.getHours(),
        endTimeMinutes = endDate.getMinutes())
    }).renderS((scope, state) => {

    def selectFromRange(range: Range, value: Int, callback: (String) => (StaffMovementPopoverState) => StaffMovementPopoverState) = {
      <.select(
        ^.defaultValue := value,
        ^.onChange ==> ((e: ReactEventI) => {
          val newValue: String = e.target.value
          scope.modState(callback(newValue))
        }),
        range.map(x => <.option(^.value := x, f"$x%02d")))
    }

    def trySaveMovement = (e: ReactEventI) => {
      val shiftTry = Shift(state.reason, state.date, f"${state.startTimeHours}%02d:${state.startTimeMinutes}%02d", f"${state.endTimeHours}%02d:${state.endTimeMinutes}%02d", s"-${state.numberOfStaff.toString}")
      shiftTry match {
        case Success(shift) =>
          for (movement <- StaffMovements.shiftsToMovements(Seq(shift))) yield {
            SPACircuit.dispatch(AddStaffMovement(movement))
            log.info(s"Dispatched AddStaffMovement(${movement}")
          }
          SPACircuit.dispatch(SaveStaffMovements())
          scope.modState(_.copy(hovered = false))
        case Failure(e) =>
          log.info("Invalid shift")
          scope.modState(_.copy(hovered = true))
      }
    }

    val popover = <.div(
      ^.onMouseEnter ==> ((e: ReactEvent) => scope.modState(_.copy(hovered = true))),
      //      ^.onMouseLeave ==> ((e: ReactEvent) => scope.modState(_.copy(hovered = false))),
      if (state.hovered) {
        PopoverWrapper(trigger = trigger, className = "staff-movement-popover", position = bottom)({
          def labelledInput(labelText: String, value: String, callback: (String) => (StaffMovementPopoverState) => StaffMovementPopoverState): ReactTagOf[html.Div] = {
            popoverFormRow(labelText, <.input.text(^.value := value, ^.onChange ==> ((e: ReactEventI) => {
              val newValue: String = e.target.value
              scope.modState(callback(newValue))
            })))
          }

          def startTimeSelector(label: String, startDate: SDate): ReactTagOf[Div] = {
            popoverFormRow(label,
              selectFromRange(
                0 to 23, startDate.getHours(),
                (v: String) => (s: StaffMovementPopoverState) => s.copy(startTimeHours = v.toInt)
              ), ":",
              selectFromRange(
                0 to 59, startDate.getMinutes(),
                (v: String) => (s: StaffMovementPopoverState) => s.copy(startTimeMinutes = v.toInt))
            )
          }

          def endTimeSelector(label: String, startDate: SDate): ReactTagOf[Div] = {
            popoverFormRow(label,
              selectFromRange(
                0 to 23, startDate.getHours(),
                (v: String) => (s: StaffMovementPopoverState) => s.copy(endTimeHours = v.toInt)
              ), ":",
              selectFromRange(
                0 to 59, startDate.getMinutes(),
                (v: String) => (s: StaffMovementPopoverState) => s.copy(endTimeMinutes = v.toInt))
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
            labelledInput("Reason", state.reason, (v: String) => (s: StaffMovementPopoverState) => s.copy(reason = v)),
            labelledInput("Date", state.date, (v: String) => (s: StaffMovementPopoverState) => s.copy(date = v)),
            startTimeSelector("Start time", startDate),
            endTimeSelector("End time", endDate),
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
