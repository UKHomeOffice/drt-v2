package drt.client.components

import drt.client.actions.Actions.{AddStaffMovements, UpdateStaffAdjustmentDialogueState}
import drt.client.components.StaffAdjustmentDialogue.roundToNearest
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.Terminals.Terminal
import drt.shared.{LoggedInUser, SDateLike}
import japgolly.scalajs.react.{CtorType, _}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html
import org.scalajs.dom.html.{Div, Select}

import scala.util.Success


case class StaffAdjustmentDialogueState(action: String,
                                        reasonPlaceholder: String,
                                        reason: String,
                                        terminalNames: Seq[Terminal],
                                        terminal: Terminal,
                                        date: String,
                                        startTimeHours: Int,
                                        startTimeMinutes: Int,
                                        endTimeHours: Int,
                                        endTimeMinutes: Int,
                                        numberOfStaff: String,
                                        loggedInUser: LoggedInUser) {
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

object StaffAdjustmentDialogueState {
  def apply(terminalNames: Seq[Terminal],
            terminal: Option[Terminal],
            reasonPlaceholder: String,
            startDate: SDateLike,
            endDate: SDateLike,
            action: String,
            numberOfStaff: Int,
            loggedInUser: LoggedInUser): StaffAdjustmentDialogueState =
    StaffAdjustmentDialogueState(
      action = action,
      reasonPlaceholder = reasonPlaceholder,
      reason = "",
      terminalNames = terminalNames,
      terminal = terminal.getOrElse(terminalNames.head),
      date = f"${startDate.getDate()}%02d/${startDate.getMonth()}%02d/${startDate.getFullYear - 2000}%02d",
      startTimeHours = startDate.getHours(),
      startTimeMinutes = roundToNearest(5)(startDate.getMinutes()),
      endTimeHours = endDate.getHours(),
      endTimeMinutes = roundToNearest(5)(endDate.getMinutes()),
      numberOfStaff = numberOfStaff.toString,
      loggedInUser = loggedInUser
    )
}

object StaffAdjustmentDialogue {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  def roundToNearest(nearest: Int)(x: Int): Int = {
    (x.toDouble / nearest).round.toInt * nearest
  }

  implicit val stateReuse: Reusability[StaffAdjustmentDialogueState] = Reusability.by(_.hashCode())

  def apply(state: StaffAdjustmentDialogueState): Component[Unit, StaffAdjustmentDialogueState, Unit, CtorType.Nullary] = ScalaComponent.builder[Unit]("staffMovementPopover")
    .initialState(state)
    .renderS((scope, state) => {
      def selectFromRange(range: Range,
                          defaultValue: Int,
                          callback: String => StaffAdjustmentDialogueState => StaffAdjustmentDialogueState,
                          applyRounding: Int => Int): VdomTagOf[Select] = {
        <.select(
          ^.defaultValue := applyRounding(defaultValue),
          ^.onChange ==> ((e: ReactEventFromInput) => {
            val newFromValue = e.target.value
            val updatedState = callback(newFromValue)(state)
            scope.modState(_ => updatedState)
          }),
          range.map(x => <.option(^.value := x, f"$x%02d")).toTagMod)
      }

      def trySaveMovement(): Unit = {
        val startTime: String = f"${state.startTimeHours}%02d:${state.startTimeMinutes}%02d"
        val endTime: String = f"${state.endTimeHours}%02d:${state.endTimeMinutes}%02d"
        val numberOfStaff: String = s"${state.action}${state.numberOfStaff.toString}"

        StaffAssignmentHelper
          .tryStaffAssignment(state.reason, state.terminal.toString, state.date, startTime, endTime, numberOfStaff, createdBy = Some(state.loggedInUser.email)) match {
          case Success(movement) =>
            val movementsToAdd = for (movement <- StaffMovements.assignmentsToMovements(Seq(movement))) yield movement
            SPACircuit.dispatch(AddStaffMovements(movementsToAdd))
            GoogleEventTracker.sendEvent(state.terminal.toString, "Add StaffMovement", movement.copy(createdBy = None).toString)
            killPopover()
          case _ =>
        }
      }

      def labelledInput(labelText: String,
                        value: String,
                        callback: String => StaffAdjustmentDialogueState => StaffAdjustmentDialogueState): VdomTagOf[html.Div] = {
        val textInput = <.input.text(^.value := value, ^.placeholder := state.reasonPlaceholder, ^.onChange ==> ((e: ReactEventFromInput) => {
          val newText = e.target.value
          val updatedState = callback(newText)(state)
          scope.modState(_ => updatedState)
        }))
        popoverFormRow(labelText, textInput)
      }

      def popoverFormRow(label: String, xs: TagMod*) = {
        <.div(^.className := "form-group row",
          <.label(label, ^.className := "col-sm-4 col-form-label"),
          <.div(^.className := "col-sm-4", xs.toTagMod))
      }

      def timeSelector(label: String,
                       hourDefault: Int,
                       minuteDefault: Int,
                       hourCallback: String => StaffAdjustmentDialogueState => StaffAdjustmentDialogueState,
                       minuteCallback: String => StaffAdjustmentDialogueState => StaffAdjustmentDialogueState
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

      def killPopover(): Unit = SPACircuit.dispatch(UpdateStaffAdjustmentDialogueState(None))

      <.div(<.div(^.className := "popover-overlay", ^.onClick --> Callback(killPopover())),
        <.div(^.className := "container", ^.id := "staff-adjustment-dialogue", ^.onClick ==> ((e: ReactEvent) => Callback(e.stopPropagation())), ^.key := "StaffAdjustments",
          labelledInput("Reason", state.reason, (v: String) => (s: StaffAdjustmentDialogueState) => s.copy(reason = v)),
          timeSelector("Start time", state.startTimeHours, state.startTimeMinutes,
            (v: String) => (s: StaffAdjustmentDialogueState) => s.copy(startTimeHours = v.toInt),
            (v: String) => (s: StaffAdjustmentDialogueState) => s.copy(startTimeMinutes = v.toInt)),
          timeSelector("End time", state.endTimeHours, state.endTimeMinutes,
            (v: String) => (s: StaffAdjustmentDialogueState) => s.copy(endTimeHours = v.toInt),
            (v: String) => (s: StaffAdjustmentDialogueState) => s.copy(endTimeMinutes = v.toInt)),
          popoverFormRow("Number of staff", <.input.text(^.value := state.numberOfStaff.toString, ^.onChange ==> ((e: ReactEventFromInput) => {
            val newStaff = e.target.value
            val updatedState = state.copy(numberOfStaff = newStaff)
            scope.modState(_ => updatedState)
          }))),
          <.div(^.className := "form-group-row",
            <.div(^.className := "col-sm-4"),
            <.div(^.className := "col-sm-6 btn-toolbar",
              <.button("Save", ^.className := "btn btn-primary", ^.onClick --> Callback(trySaveMovement())),
              <.button("Cancel", ^.className := "btn btn-default", ^.onClick --> Callback(killPopover()))))))
    })
    .configure(Reusability.shouldComponentUpdate)
    .build
}
