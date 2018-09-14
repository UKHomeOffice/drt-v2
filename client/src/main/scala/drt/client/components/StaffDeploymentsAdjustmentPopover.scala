package drt.client.components

import drt.client.actions.Actions.{AddStaffMovement, SaveStaffMovements}
import drt.client.modules.GoogleEventTracker
import drt.client.services._
import drt.shared.FlightsApi.TerminalName
import drt.shared.{LoggedInUser, SDateLike, StaffAssignment}
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html
import org.scalajs.dom.html.Div

import scala.util.{Failure, Success}

object StaffDeploymentsAdjustmentPopover {

  case class StaffDeploymentAdjustmentPopoverState(
                                                    active: Boolean = false,
                                                    reason: String = "",
                                                    terminalName: TerminalName,
                                                    date: String = "",
                                                    startTimeHours: Int = 0,
                                                    startTimeMinutes: Int = 0,
                                                    endTimeHours: Int = 0,
                                                    endTimeMinutes: Int = 0,
                                                    numberOfStaff: String = "1",
                                                    loggedInUser: LoggedInUser
                                                  )

  def roundToNearest(nearest: Int)(x: Int): Int = {
    (x.toDouble / nearest).round.toInt * nearest
  }

  def selectTerminal(defaultValue: String, callback: (ReactEventFromInput) => Callback, terminalNames: Seq[String]) = {
    <.select(
      ^.defaultValue := defaultValue,
      ^.onChange ==> callback,
      terminalNames.map(x => <.option(^.value := x, x)).toTagMod)
  }

  def apply(terminalNames: Seq[TerminalName], terminal: Option[TerminalName], trigger: String, reasonPlaceholder: String, startDate: SDateLike, endDate: SDateLike, popoverPosition: String, action: String = "-", loggedInUser: LoggedInUser) = ScalaComponent.builder[Unit]("staffMovementPopover")
    .initialState(
      StaffDeploymentAdjustmentPopoverState(
        reason = "",
        terminalName = terminal.getOrElse(terminalNames.head),
        date = f"${startDate.getDate()}%02d/${startDate.getMonth()}%02d/${startDate.getFullYear - 2000}%02d",
        startTimeHours = startDate.getHours(),
        startTimeMinutes = roundToNearest(5)(startDate.getMinutes()),
        endTimeHours = endDate.getHours(),
        endTimeMinutes = roundToNearest(5)(endDate.getMinutes()),
        loggedInUser = loggedInUser
      )
    ).renderS((scope, state) => {


    def selectFromRange(range: Range, defaultValue: Int, callback: (String) => (StaffDeploymentAdjustmentPopoverState) => StaffDeploymentAdjustmentPopoverState, applyRounding: Int => Int) = {
      <.select(
        ^.defaultValue := applyRounding(defaultValue),
        ^.onChange ==> ((e: ReactEventFromInput) => scope.modState(callback(e.target.value))
          ),
        range.map(x => <.option(^.value := x, f"$x%02d")).toTagMod)
    }

    def trySaveMovement = (e: ReactEventFromInput) => {
      val startTime: String = f"${state.startTimeHours}%02d:${state.startTimeMinutes}%02d"
      val endTime: String = f"${state.endTimeHours}%02d:${state.endTimeMinutes}%02d"
      val numberOfStaff: String = s"$action${state.numberOfStaff.toString}"
      val shiftTry = StaffAssignmentHelper.tryStaffAssignment(state.reason, state.terminalName, state.date, startTime, endTime, numberOfStaff, createdBy = Some(loggedInUser.email))
      shiftTry match {
        case Success(shift) =>
          for (movement <- StaffMovements.assignmentsToMovements(Seq(shift))) yield {
            SPACircuit.dispatch(AddStaffMovement(movement))
          }
          GoogleEventTracker.sendEvent(state.terminalName, "Save Staff Assignment", shift.copy(createdBy = None).toString)
          SPACircuit.dispatch(SaveStaffMovements(shift.terminalName))
          scope.modState(_.copy(active = false))
        case Failure(_) =>
          scope.modState(_.copy(active = true))
      }
    }

    def labelledInput(labelText: String, value: String, callback: (String) => (StaffDeploymentAdjustmentPopoverState) => StaffDeploymentAdjustmentPopoverState, placeHolder: String = ""): VdomTagOf[html.Div] = {
      popoverFormRow(labelText, <.input.text(^.value := value, ^.placeholder := reasonPlaceholder, ^.onChange ==> ((e: ReactEventFromInput) => {
        val newValue: String = e.target.value
        scope.modState(callback(newValue))
      })))
    }

    def popoverFormRow(label: String, xs: TagMod*) = {
      <.div(^.className := "form-group row",
        <.label(label, ^.className := "col-sm-4 col-form-label"),
        <.div(^.className := "col-sm-4", xs.toTagMod))
    }

    def timeSelector(label: String,
                     hourDefault: Int,
                     minuteDefault: Int,
                     hourCallback: (String) => (StaffDeploymentAdjustmentPopoverState) => StaffDeploymentAdjustmentPopoverState,
                     minuteCallback: (String) => (StaffDeploymentAdjustmentPopoverState) => StaffDeploymentAdjustmentPopoverState
                    ): VdomTagOf[Div] = {
      popoverFormRow(label,
        selectFromRange(
          0 to 23, hourDefault, hourCallback, (x) => x
        ), ":",
        selectFromRange(
          0 to 59 by 5, minuteDefault, minuteCallback, roundToNearest(5)
        )
      )
    }

    def hoveredComponent: TagMod = if (state.active) {
      val popoverChildren = <.div(<.div(^.className:="popover-overlay", ^.onClick ==> showPopover(false)),
        <.div(^.className := "container", ^.onClick ==> ((e: ReactEvent) => Callback(e.stopPropagation())), ^.key := "StaffAdjustments",
        labelledInput("Reason", state.reason, (v: String) => (s: StaffDeploymentAdjustmentPopoverState) => s.copy(reason = v)),
        terminal match {
          case None => popoverFormRow ("Terminal", selectTerminal (state.terminalName, (e: ReactEventFromInput) =>
        scope.modState (_.copy (terminalName = e.target.value) ), terminalNames) )
          case _ => ""
        },
        timeSelector("Start time", state.startTimeHours, state.startTimeMinutes,
          (v: String) => (s: StaffDeploymentAdjustmentPopoverState) => s.copy(startTimeHours = v.toInt),
          (v: String) => (s: StaffDeploymentAdjustmentPopoverState) => s.copy(startTimeMinutes = v.toInt)),
        timeSelector("End time", state.endTimeHours, state.endTimeMinutes,
          (v: String) => (s: StaffDeploymentAdjustmentPopoverState) => s.copy(endTimeHours = v.toInt),
          (v: String) => (s: StaffDeploymentAdjustmentPopoverState) => s.copy(endTimeMinutes = v.toInt)),
        popoverFormRow("Number of staff", <.input.text(^.value := state.numberOfStaff.toString, ^.onChange ==> ((e: ReactEventFromInput) => {
          val newValue = e.target.value
          scope.modState((s: StaffDeploymentAdjustmentPopoverState) => s.copy(numberOfStaff = newValue))
        }))),
        <.div(^.className := "form-group-row",
          <.div(^.className := "col-sm-4"),
          <.div(^.className := "col-sm-6 btn-toolbar",
            <.button("Save", ^.className := "btn btn-primary", ^.onClick ==> trySaveMovement),
            <.button("Cancel", ^.className := "btn btn-default", ^.onClick ==> showPopover(false))))))

      popoverChildren
    } else {
      ""
    }
    def showPopover(show: Boolean) = (e: ReactEventFromInput) => scope.modState(_.copy(active = show))

    val popover = <.div(hoveredComponent, ^.className := "staff-deployment-adjustment-container",
      <.div(^.className := "popover-trigger", ^.onClick ==> showPopover(true), trigger))
    popover
  }).build
}
