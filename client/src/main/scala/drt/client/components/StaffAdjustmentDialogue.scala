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
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html
import org.scalajs.dom.html.{Div, Select}

import scala.util.{Success, Try}


case class StaffAdjustmentDialogueState(action: String,
                                        reasonPlaceholder: String,
                                        reason: String,
                                        maybeReasonAdditional: Option[String],
                                        terminalNames: Iterable[Terminal],
                                        terminal: Terminal,
                                        date: String,
                                        startTimeHours: Int,
                                        startTimeMinutes: Int,
                                        lengthOfTimeMinutes: Int,
                                        numberOfStaff: Int,
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

  def staffLabel: String = if (action == "+") "Additional staff" else "Staff removed"

  def fullReason: String = (List(reason) ++ maybeReasonAdditional.toList).mkString(": ")

  def endHoursAndMinutes: (Int, Int) = {
    val endTime = (startTimeHours * 60) + startTimeMinutes + lengthOfTimeMinutes
    val endHours = endTime / 60
    val endMinutes = endTime % 60
    (endHours, endMinutes)
  }

}

object StaffAdjustmentDialogueState {
  def apply(terminalNames: Iterable[Terminal],
            terminal: Option[Terminal],
            reasonPlaceholder: String,
            startDate: SDateLike,
            timeLengthMinutes: Int,
            action: String,
            numberOfStaff: Int,
            loggedInUser: LoggedInUser): StaffAdjustmentDialogueState =
    StaffAdjustmentDialogueState(
      action = action,
      reasonPlaceholder = reasonPlaceholder,
      reason = "Other",
      None,
      terminalNames = terminalNames,
      terminal = terminal.getOrElse(terminalNames.head),
      date = f"${startDate.getDate()}%02d/${startDate.getMonth()}%02d/${startDate.getFullYear - 2000}%02d",
      startTimeHours = startDate.getHours(),
      startTimeMinutes = roundToNearest(5)(startDate.getMinutes()),
      lengthOfTimeMinutes = timeLengthMinutes,
      numberOfStaff = numberOfStaff,
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
    .renderS { (scope, state) =>
      def timesBy15Minutes(startHour: Int): Iterable[String] = for {
        hour <- startHour until 24
        minute <- 0 to 45 by 15
      } yield f"$hour%02d:$minute%02d"

      def selectFromRange(range: Iterable[String],
                          defaultValue: String,
                          className: String,
                          callback: String => StaffAdjustmentDialogueState => StaffAdjustmentDialogueState
                         ): VdomTagOf[Select] = {
        <.select(
          ^.value := defaultValue,
          ^.className := className,
          ^.onChange ==> ((e: ReactEventFromInput) => {
            val newFromValue = e.target.value
            val updatedState = callback(newFromValue)(scope.state)
            scope.setState(updatedState)
          }),
          range.map { value => <.option(^.value := value, value) }.toTagMod)
      }

      def killPopover(): Unit = SPACircuit.dispatch(UpdateStaffAdjustmentDialogueState(None))

      def trySaveMovement(): Unit = {
        val startTime: String = f"${state.startTimeHours}%02d:${state.startTimeMinutes}%02d"
        val numberOfStaff: String = s"${state.action}${state.numberOfStaff.toString}"

        StaffAssignmentHelper
          .tryStaffAssignment(state.fullReason, state.terminal.toString, state.date, startTime, state.lengthOfTimeMinutes, numberOfStaff, createdBy = Option(state.loggedInUser.email)) match {
          case Success(movement) =>
            val movementsToAdd = for (movement <- StaffMovements.assignmentsToMovements(Seq(movement))) yield movement
            SPACircuit.dispatch(AddStaffMovements(movementsToAdd))
            GoogleEventTracker.sendEvent(state.terminal.toString, "Add StaffMovement", movement.copy(createdBy = None).toString)
            killPopover()
          case _ =>
        }
      }

      def additionalInfoInput(state: StaffAdjustmentDialogueState): VdomTagOf[html.Div] = {
        val callback = (v: String) => (s: StaffAdjustmentDialogueState) => s.copy(maybeReasonAdditional = Option(v))
        val value = state.maybeReasonAdditional.getOrElse("")
        val textInput = <.input.text(
          ^.value := value,
          ^.className := "staff-adjustment--additional-info",
          ^.placeholder := state.reasonPlaceholder,
          ^.onChange ==> ((e: ReactEventFromInput) => {
            val newText = e.target.value
            val updatedState = callback(newText)(state)
            scope.setState(updatedState)
          }))
        popoverFormRow("", None, textInput)
      }

      val adjustStartAndEndTimeFromString: String => StaffAdjustmentDialogueState => StaffAdjustmentDialogueState = (v: String) => (s: StaffAdjustmentDialogueState) => {
        val Array(hours, minutes) = v.split(":").map(_.toInt)
        s.copy(startTimeHours = hours, startTimeMinutes = minutes)
      }

      val adjustLengthFromString = (v: String) => (s: StaffAdjustmentDialogueState) => {
        s.copy(lengthOfTimeMinutes = v.toInt)
      }

      def timeSelector(): VdomTagOf[Div] = popoverFormRow(
        "Time",
        Option("staff-adjustment--start-time"),
        selectFromRange(timesBy15Minutes(0), f"${state.startTimeHours}%02d:${state.startTimeMinutes}%02d", "staff-adjustment--select-start-time", adjustStartAndEndTimeFromString),
        " for ",
        selectFromRange(0 until 1440 by 15 map (m => s"$m"), s"${state.lengthOfTimeMinutes}", "staff-adjustment--select-time-length", adjustLengthFromString),
        " minutes"
      )

      val adjustLengthFromEndTime: String => StaffAdjustmentDialogueState => StaffAdjustmentDialogueState = (v: String) => (s: StaffAdjustmentDialogueState) => {
        val Array(hours, minutes) = v.split(":").map(_.toInt)
        val startMins = (state.startTimeHours * 60) + state.startTimeMinutes
        val endMins = (hours * 60) + minutes
        val diff = endMins - startMins

        s.copy(lengthOfTimeMinutes = diff)
      }

      def endTimeSelector(): VdomTagOf[Div] = {
        val (endHours, endMinutes) = state.endHoursAndMinutes
        popoverFormRow(
          "Ending at",
          Option("staff-adjustment--end-time"),
          selectFromRange(timesBy15Minutes(state.startTimeHours), f"${endHours}%02d:${endMinutes}%02d", "staff-adjustment--select-end-time", adjustLengthFromEndTime)
        )
      }

      def reasonSelector(): VdomTagOf[Div] = popoverFormRow(
        "Reason",
        Option("staff-adjustment--start-time"),
        selectFromRange(movementReasons, state.reason, "staff-adjustment--select-reason", (v: String) => (s: StaffAdjustmentDialogueState) => s.copy(reason = v))
      )

      def staffAdjustments(): html_<^.VdomTagOf[Div] = {
        popoverFormRow(
          state.staffLabel,
          None,
          <.a(
            Icon.minus,
            ^.className := "staff-adjustment--adjustment-button staff-adjustment--adjustment-button__decrease",
            ^.onClick ==> ((_: ReactEventFromInput) => {
              val newValue = if (state.numberOfStaff > 1) state.numberOfStaff - 1 else 0
              val updatedState = state.copy(numberOfStaff = newValue)
              scope.setState(updatedState)
            })),
          <.input.text(
            ^.value := state.numberOfStaff.toString,
            ^.className := "staff-adjustment--num-staff",
            ^.onChange ==> ((e: ReactEventFromInput) => {
              val newStaff = Try(e.target.value.toInt).getOrElse(0)
              val updatedState = state.copy(numberOfStaff = newStaff)
              scope.setState(updatedState)
            })),
          <.a(
            Icon.plus,
            ^.className := "staff-adjustment--adjustment-button staff-adjustment--adjustment-button__increase",
            ^.onClick ==> ((_: ReactEventFromInput) => {
              val newValue = state.numberOfStaff + 1
              val updatedState = state.copy(numberOfStaff = newValue)
              scope.setState(updatedState)
            }))
        )
      }

      <.div(
        <.div(^.className := "popover-overlay", ^.onClick --> Callback(killPopover())),
        <.div(
          ^.className := "container",
          ^.id := "staff-adjustment-dialogue",
          ^.onClick ==> ((e: ReactEvent) => Callback(e.stopPropagation())),
          <.h2(titleFromAction(state.action)),
          reasonSelector(),
          additionalInfoInput(state),
          timeSelector(),
          endTimeSelector(),
          staffAdjustments(),
          saveAndCancel(killPopover _, trySaveMovement _)
        )
      )
    }
    .configure(Reusability.shouldComponentUpdate)
    .build

  def popoverFormRow(label: String, maybeClassName: Option[String], xs: TagMod*): VdomTagOf[Div] = {
    val classes = List("form-group", "row") ++ maybeClassName.toList
    <.div(^.className := classes.mkString(" "),
      <.label(label, ^.className := "col-sm-4 col-form-label"),
      <.div(^.className := "col-sm-7", xs.toTagMod))
  }

  val movementReasons = Iterable(
    "Acting up",
    "Bag search",
    "Briefing",
    "Case working",
    "CL - Compensatory leave",
    "CWA - Controlled waiting area",
    "Discipline move (customs / immigration)",
    "Emergency leave",
    "Medical",
    "Meeting",
    "No show",
    "Other",
    "SEA - Secondary Examination Area",
    "Shift Change / Swap",
    "Sick",
    "Special Ops (CTA, BDO duties, Gate checks etc)",
    "Terminal move",
    "TOIL - Time off in lieu",
    "Training"
  )

  def saveAndCancel(killPopover: () => Unit, trySaveMovement: () => Unit): VdomTagOf[Div] = {
    <.div(^.className := "form-group-row",
      <.div(^.className := "col-sm-4"),
      <.div(^.className := "col-sm-8 btn-toolbar",
        <.button("Cancel", ^.className := "btn btn-default staff-adjustment--save-cancel", ^.onClick --> Callback(killPopover())),
        <.button("Save", ^.className := "btn btn-primary staff-adjustment--save-cancel", ^.onClick --> Callback(trySaveMovement()))
      ))
  }

  def titleFromAction(action: String): String = {
    if (action == "+") "Increase available staff" else "Decrease available staff"
  }
}
