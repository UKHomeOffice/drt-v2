package drt.client.components

import drt.client.actions.Actions.AddStaffMovements
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.FlightsApi.TerminalName
import drt.shared.{LoggedInUser, SDateLike}
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html
import org.scalajs.dom.html.{Div, Select}

import scala.util.{Failure, Success}

object StaffDeploymentsAdjustmentPopover {

  case class StaffDeploymentAdjustmentPopoverState(active: Boolean,
                                                   action: String,
                                                   reasonPlaceholder: String,
                                                   reason: String,
                                                   terminalNames: Seq[TerminalName],
                                                   terminalName: TerminalName,
                                                   date: String,
                                                   startTimeHours: Int,
                                                   startTimeMinutes: Int,
                                                   endTimeHours: Int,
                                                   endTimeMinutes: Int,
                                                   numberOfStaff: String,
                                                   loggedInUser: LoggedInUser
                                                  ) {
    def isApplicableToSlot(slotStart: SDateLike, slotEnd: SDateLike): Boolean = {
      val startDate = SDate(s"${date}T$startTimeHours:$startTimeMinutes")

      slotStart <= startDate && startDate <= slotEnd
    }
  }

  object StaffDeploymentAdjustmentPopoverState {
    def apply(terminalNames: Seq[TerminalName],
              terminal: Option[TerminalName],
              trigger: String,
              reasonPlaceholder: String,
              startDate: SDateLike,
              endDate: SDateLike,
              popoverPosition: String,
              action: String = "-",
              loggedInUser: LoggedInUser): StaffDeploymentAdjustmentPopoverState =
      StaffDeploymentAdjustmentPopoverState(
        active = true,
        action = action,
        reasonPlaceholder = reasonPlaceholder,
        reason = "",
        terminalNames = terminalNames,
        terminalName = terminal.getOrElse(terminalNames.head),
        date = f"${startDate.getDate()}%02d/${startDate.getMonth()}%02d/${startDate.getFullYear - 2000}%02d",
        startTimeHours = startDate.getHours(),
        startTimeMinutes = roundToNearest(5)(startDate.getMinutes()),
        endTimeHours = endDate.getHours(),
        endTimeMinutes = roundToNearest(5)(endDate.getMinutes()),
        numberOfStaff = "1",
        loggedInUser = loggedInUser
      )
  }

  case class StaffDeploymentAdjustmentPopoverProps(updateState: Option[StaffDeploymentsAdjustmentPopover.StaffDeploymentAdjustmentPopoverState] => Unit)

  def roundToNearest(nearest: Int)(x: Int): Int = {
    (x.toDouble / nearest).round.toInt * nearest
  }

  def selectTerminal(defaultValue: String,
                     callback: ReactEventFromInput => Callback,
                     terminalNames: Seq[String]): VdomTagOf[Select] = {
    <.select(
      ^.defaultValue := defaultValue,
      ^.onChange ==> callback,
      terminalNames.map(x => <.option(^.value := x, x)).toTagMod)
  }

  def apply(state: StaffDeploymentAdjustmentPopoverState) = ScalaComponent.builder[StaffDeploymentAdjustmentPopoverProps]("staffMovementPopover")
    .initialState(state)
    .renderPS((scope, props, state) => {
      def selectFromRange(range: Range,
                          defaultValue: Int,
                          callback: String => StaffDeploymentAdjustmentPopoverState => StaffDeploymentAdjustmentPopoverState,
                          applyRounding: Int => Int) = {
        <.select(
          ^.defaultValue := applyRounding(defaultValue),
          ^.onChange ==> ((e: ReactEventFromInput) => scope.modState(callback(e.target.value))
            ),
          range.map(x => <.option(^.value := x, f"$x%02d")).toTagMod)
      }

      def trySaveMovement = (e: ReactEventFromInput) => {
        val startTime: String = f"${state.startTimeHours}%02d:${state.startTimeMinutes}%02d"
        val endTime: String = f"${state.endTimeHours}%02d:${state.endTimeMinutes}%02d"
        val numberOfStaff: String = s"${state.action}${state.numberOfStaff.toString}"

        StaffAssignmentHelper
          .tryStaffAssignment(state.reason, state.terminalName, state.date, startTime, endTime, numberOfStaff, createdBy = Some(state.loggedInUser.email)) match {
          case Success(movement) =>
            val movementsToAdd = for (movement <- StaffMovements.assignmentsToMovements(Seq(movement))) yield movement
            SPACircuit.dispatch(AddStaffMovements(movementsToAdd))
            GoogleEventTracker.sendEvent(state.terminalName, "Add StaffMovement", movement.copy(createdBy = None).toString)
            scope.modState(_.copy(active = false))
          case Failure(_) =>
            scope.modState(_.copy(active = true))
        }
      }

      def labelledInput(labelText: String,
                        value: String,
                        callback: String => StaffDeploymentAdjustmentPopoverState => StaffDeploymentAdjustmentPopoverState,
                        placeHolder: String = ""): VdomTagOf[html.Div] = {
        popoverFormRow(labelText, <.input.text(^.value := value, ^.placeholder := state.reasonPlaceholder, ^.onChange ==> ((e: ReactEventFromInput) => {
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
                       hourCallback: String => StaffDeploymentAdjustmentPopoverState => StaffDeploymentAdjustmentPopoverState,
                       minuteCallback: String => StaffDeploymentAdjustmentPopoverState => StaffDeploymentAdjustmentPopoverState
                      ): VdomTagOf[Div] = {
        popoverFormRow(label,
          selectFromRange(
            0 to 23, hourDefault, hourCallback, x => x
          ), ":",
          selectFromRange(
            0 to 59 by 5, minuteDefault, minuteCallback, roundToNearest(5)
          )
        )
      }

      def hoveredComponent: TagMod = if (state.active) {
        <.div(<.div(^.className := "popover-overlay", ^.onClick ==> showPopover(false)),
          <.div(^.className := "container", ^.onClick ==> ((e: ReactEvent) => Callback(e.stopPropagation())), ^.key := "StaffAdjustments",
            labelledInput("Reason", state.reason, (v: String) => (s: StaffDeploymentAdjustmentPopoverState) => s.copy(reason = v)),
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
      } else {
        ""
      }

      def showPopover(show: Boolean) = (_: ReactEventFromInput) => {
        scope.modState(existingState => {
          val updatedState = existingState.copy(active = show)
          props.updateState(Option(updatedState))
          updatedState
        })
      }

      <.div(hoveredComponent, ^.className := "staff-deployment-adjustment-container",
        <.div(^.className := "popover-trigger", ^.onClick ==> showPopover(true), state.action))
    }).build
}
