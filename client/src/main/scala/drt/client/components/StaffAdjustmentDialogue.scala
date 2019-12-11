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

import scala.util.{Success, Try}


case class StaffAdjustmentDialogueState(action: String,
                                        reasonPlaceholder: String,
                                        reason: String,
                                        maybeReasonAdditional: Option[String],
                                        terminalNames: Seq[Terminal],
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
}

object StaffAdjustmentDialogueState {
  def apply(terminalNames: Seq[Terminal],
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
    .renderS((scope, state) => {
      def timesBy15Minutes: Iterable[String] = for {
        hour <- 0 until 24
        minute <- 0 to 45 by 15
      } yield f"$hour%02d:$minute%02d"

      def selectFromRange(range: Iterable[String],
                          defaultValue: String,
                          callback: String => StaffAdjustmentDialogueState => StaffAdjustmentDialogueState
                         ): VdomTagOf[Select] = {
        <.select(
          ^.defaultValue := defaultValue,
          ^.onChange ==> ((e: ReactEventFromInput) => {
            val newFromValue = e.target.value
            val updatedState = callback(newFromValue)(state)
            scope.modState(_ => updatedState)
          }),
          range.map(x => <.option(^.value := x, x)).toTagMod)
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

      def additionalInfoInput(labelText: String,
                              lassName: Option[String],
                              value: String,
                              callback: String => StaffAdjustmentDialogueState => StaffAdjustmentDialogueState): VdomTagOf[html.Div] = {
        val textInput = <.input.text(
          ^.value := value,
          ^.className := "staff-adjustment--additional-info",
          ^.placeholder := state.reasonPlaceholder,
          ^.onChange ==> ((e: ReactEventFromInput) => {
            val newText = e.target.value
            val updatedState = callback(newText)(state)
            scope.modState(_ => updatedState)
          }))
        popoverFormRow("", None, textInput)
      }

      def popoverFormRow(label: String, maybeClassName: Option[String], xs: TagMod*): VdomTagOf[Div] = {
        val classes = List("form-group", "row") ++ maybeClassName.toList
        <.div(^.className := classes.mkString(" "),
          <.label(label, ^.className := "col-sm-4 col-form-label"),
          <.div(^.className := "col-sm-7", xs.toTagMod))
      }

      def timeSelector(label: String,
                       hourDefault: Int,
                       minuteDefault: Int,
                       hourCallback: String => StaffAdjustmentDialogueState => StaffAdjustmentDialogueState,
                       minuteCallback: String => StaffAdjustmentDialogueState => StaffAdjustmentDialogueState
                      ): VdomTagOf[Div] = popoverFormRow(
        label,
        Option("staff-adjustment--start-time"),
        selectFromRange(timesBy15Minutes, f"$hourDefault%02d:$minuteDefault%02d", hourCallback),
        " for ",
        selectFromRange(15 to 120 by 15 map (m => s"$m"), "60", (v: String) => (s: StaffAdjustmentDialogueState) => s.copy(lengthOfTimeMinutes = v.toInt)),
        " minutes"
      )

      val movementReasons = Iterable(
        "Acting up",
        "Ambulance staff",
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
        "Special Ops (CTA, BDO duties, Gate checks etc)"
      )

      def reasonSelector(reasonCallback: String => StaffAdjustmentDialogueState => StaffAdjustmentDialogueState): VdomTagOf[Div] = popoverFormRow(
        "Reason",
        Option("staff-adjustment--start-time"),
        selectFromRange(movementReasons, "Other", reasonCallback)
      )

      <.div(<.div(^.className := "popover-overlay", ^.onClick --> Callback(killPopover())),
        <.div(
          ^.className := "container",
          ^.id := "staff-adjustment-dialogue",
          ^.onClick ==> ((e: ReactEvent) => Callback(e.stopPropagation())), ^.key := "StaffAdjustments",
          <.h2(if (state.action == "+") "Increase available staff" else "Decrease available staff"),
          reasonSelector((v: String) => (s: StaffAdjustmentDialogueState) => s.copy(reason = v)),
          additionalInfoInput("additional info", Option("staff-adjustment--reason"), state.maybeReasonAdditional.getOrElse(""), (v: String) => (s: StaffAdjustmentDialogueState) => s.copy(maybeReasonAdditional = Option(v))),
          timeSelector(
            "Time",
            state.startTimeHours,
            state.startTimeMinutes,
            (v: String) => (s: StaffAdjustmentDialogueState) => s.copy(startTimeHours = v.toInt),
            (v: String) => (s: StaffAdjustmentDialogueState) => s.copy(startTimeMinutes = v.toInt)),

          popoverFormRow(
            state.staffLabel,
            None,
            <.input.text(
              ^.value := state.numberOfStaff.toString,
              ^.className := "staff-adjustment--num-staff",
              ^.onChange ==> ((e: ReactEventFromInput) => {
                val newStaff = Try(e.target.value.toInt).getOrElse(0)
                val updatedState = state.copy(numberOfStaff = newStaff)
                scope.modState(_ => updatedState)
              })),
            <.a(
              Icon.plus,
              ^.className := "staff-adjustment--adjustment-button",
              ^.onClick ==> ((e: ReactEventFromInput) => {
                val newValue = state.numberOfStaff + 1
                val updatedState = state.copy(numberOfStaff = newValue)
                scope.setState(updatedState)
              })),
            <.a(
              Icon.minus,
              ^.className := "staff-adjustment--adjustment-button",
              ^.onClick ==> ((e: ReactEventFromInput) => {
                val newValue = if (state.numberOfStaff > 1) state.numberOfStaff - 1 else 0
                val updatedState = state.copy(numberOfStaff = newValue)
                scope.setState(updatedState)
              }))
          ),
          <.div(^.className := "form-group-row",
            <.div(^.className := "col-sm-4"),
            <.div(^.className := "col-sm-8 btn-toolbar",
              <.button("Cancel", ^.className := "btn btn-default staff-adjustment--save-cancel", ^.onClick --> Callback(killPopover())),
              <.button("Save", ^.className := "btn btn-primary staff-adjustment--save-cancel", ^.onClick --> Callback(trySaveMovement()))
            ))
        )
      )
    })
    .configure(Reusability.shouldComponentUpdate)
    .build
}
