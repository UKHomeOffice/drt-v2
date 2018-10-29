package drt.client.components

import drt.client.actions.Actions.{AddStaffMovements, UpdateStaffAdjustmentPopOver}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.FlightsApi.TerminalName
import drt.shared.{LoggedInUser, SDateLike}
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html
import org.scalajs.dom.html.{Div, Select}

import scala.util.Success

object StaffDeploymentsAdjustmentPopover {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

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
      date.split("/").toList match {
        case d :: m :: y :: Nil =>
          val yyyy = f"${y.toInt + 2000}%02d"
          val mm = f"${m.toInt}%02d"
          val dd = f"${d.toInt}%02d"
          val startDate = SDate(f"$yyyy-$mm-${dd}T$startTimeHours%02d:$startTimeMinutes%02d")

          slotStart <= startDate && startDate <= slotEnd
        case _ => false
      }
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
              action: String,
              numberOfStaff: Int,
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
        numberOfStaff = numberOfStaff.toString,
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
          ^.onChange ==> ((e: ReactEventFromInput) => scope.modState(s => {
            val updatedState = callback(e.target.value)(s)
            props.updateState(Option(updatedState))
            updatedState
          })
            ),
          range.map(x => <.option(^.value := x, f"$x%02d")).toTagMod)
      }

      def trySaveMovement(): Unit = {
        val startTime: String = f"${state.startTimeHours}%02d:${state.startTimeMinutes}%02d"
        val endTime: String = f"${state.endTimeHours}%02d:${state.endTimeMinutes}%02d"
        val numberOfStaff: String = s"${state.action}${state.numberOfStaff.toString}"

        StaffAssignmentHelper
          .tryStaffAssignment(state.reason, state.terminalName, state.date, startTime, endTime, numberOfStaff, createdBy = Some(state.loggedInUser.email)) match {
          case Success(movement) =>
            val movementsToAdd = for (movement <- StaffMovements.assignmentsToMovements(Seq(movement))) yield movement
            SPACircuit.dispatch(AddStaffMovements(movementsToAdd))
            GoogleEventTracker.sendEvent(state.terminalName, "Add StaffMovement", movement.copy(createdBy = None).toString)
            killPopover()
          case _ =>
        }
      }

      def labelledInput(labelText: String,
                        value: String,
                        callback: String => StaffDeploymentAdjustmentPopoverState => StaffDeploymentAdjustmentPopoverState,
                        placeHolder: String = ""): VdomTagOf[html.Div] = {
        popoverFormRow(labelText, <.input.text(^.value := value, ^.placeholder := state.reasonPlaceholder, ^.onChange ==> ((e: ReactEventFromInput) => {
          val newValue: String = e.target.value
          scope.modState(s => {
            val updatedState = callback(newValue)(s)
            props.updateState(Option(updatedState))
            updatedState
          })
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

      def killPopover(): Unit = {
        val updatedState = state.copy(active = false)
        SPACircuit.dispatch(UpdateStaffAdjustmentPopOver(Option(updatedState)))
      }

      <.div(<.div(^.className := "popover-overlay", ^.onClick --> Callback(killPopover())),
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
            scope.modState(s => {
              val updatedState = s.copy(numberOfStaff = newValue)
              props.updateState(Option(updatedState))
              updatedState
            })
          }))),
          <.div(^.className := "form-group-row",
            <.div(^.className := "col-sm-4"),
            <.div(^.className := "col-sm-6 btn-toolbar",
              <.button("Save", ^.className := "btn btn-primary", ^.onClick --> Callback(trySaveMovement())),
              <.button("Cancel", ^.className := "btn btn-default", ^.onClick --> Callback(killPopover()))))))
    }).build
}
