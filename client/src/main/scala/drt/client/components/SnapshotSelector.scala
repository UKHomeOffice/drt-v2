package drt.client.components

import diode.react.ModelProxy
import drt.client.actions.Actions.{SetViewMode, ShowLoader}
import drt.client.logger.LoggerFactory
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.SDateLike
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}

import scala.scalajs.js.Date

object SnapshotSelector {

  val earliestAvailable = SDate(2017, 10, 6)

  val log = LoggerFactory.getLogger("SnapshotSelector")

  case class Props()

  case class State(showDatePicker: Boolean, day: Int, month: Int, year: Int, hours: Int, minutes: Int) {
    def snapshotDateTime = SDate(year, month + 1, day, hours, minutes)
  }

  val today = new Date()
  val initialState = State(false, today.getDate(), today.getMonth(), today.getFullYear(), today.getHours(), today.getMinutes())

  def formRow(label: String, xs: TagMod*) = {
    <.div(^.className := "form-group row",
      <.label(label, ^.className := "col-sm-1 col-form-label"),
      <.div(^.className := "col-sm-8", xs.toTagMod))
  }

  def isLaterThanEarliest(dateTime: SDateLike) = {
    dateTime.millisSinceEpoch > earliestAvailable.millisSinceEpoch
  }

  implicit val stateReuse: Reusability[State] = Reusability.by(_.hashCode())
  implicit val propsReuse: Reusability[Props] = Reusability.always

  val component = ScalaComponent.builder[Props]("SnapshotSelector")
    .initialState(
      State(true, today.getDate(), today.getMonth(), today.getFullYear(), today.getHours(), today.getMinutes())
    )
    .renderS((scope, state) => {
      val months = Seq("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December").zipWithIndex
      val days = Seq.range(1, 31)
      val years = Seq.range(2017, today.getFullYear() + 1)
      val hours = Seq.range(0, 24)

      val minutes = Seq.range(0, 60)

      def drawSelect(values: Seq[String], names: Seq[String], defaultValue: Int, callback: (String) => (State) => State) = {
        val nameValues = values.zip(names)
        <.select(^.className := "form-control", ^.defaultValue := defaultValue.toString,
          ^.onChange ==> ((e: ReactEventFromInput) => scope.modState(callback(e.target.value))),
          nameValues.map {
            case (name, value) => <.option(^.value := value, name)
          }.toTagMod)
      }

      def daysInMonth(month: Int, year: Int) = new Date(year, month, 0).getDate()

      def isValidSnapshotDate = isLaterThanEarliest(state.snapshotDateTime) && isInPast

      def selectPointInTime = (e: ReactEventFromInput) => {
        if (isValidSnapshotDate) {
          SPACircuit.dispatch(ShowLoader(s"Loading snapshot for ${state.snapshotDateTime.prettyDateTime()}..."))
          SPACircuit.dispatch(SetViewMode(ViewPointInTime(state.snapshotDateTime)))
          scope.modState(_.copy(showDatePicker = false))
        } else {
          scope.modState(_.copy(showDatePicker = true))
        }
      }

      def isInPast = {
        state.snapshotDateTime.millisSinceEpoch < SDate.now().millisSinceEpoch
      }

      <.div(
        <.div(
          if (state.showDatePicker) {
            val errorMessage = if (!isInPast) <.div(^.className := "error-message", "Please select a date in the past") else if
            (!isLaterThanEarliest(state.snapshotDateTime)) <.div(^.className := "error-message", s"Earliest available is ${earliestAvailable.prettyDateTime()}") else <.div()

            <.div(
              <.div(
                <.div(^.className := "form-group row snapshot-selector",
                  List(
                    <.div(^.className := "col-sm-3 no-gutters", <.label("Choose Snapshot", ^.className := "col-form-label")),
                    <.div(^.className := "col-sm-2 no-gutters", drawSelect(months.map(_._1.toString), months.map(_._2.toString), state.month, (v: String) => (s: State) => s.copy(month = v.toInt))),
                    <.div(^.className := "col-sm-1 no-gutters", drawSelect(List.range(1, daysInMonth(state.month, state.year) + 1).map(_.toString), days.map(_.toString), state.day, (v: String) => (s: State) => s.copy(day = v.toInt))),
                    <.div(^.className := "col-sm-2 no-gutters", drawSelect(years.map(_.toString), years.map(_.toString), state.day, (v: String) => (s: State) => s.copy(year = v.toInt))),
                    <.div(^.className := "col-sm-1 no-gutters", drawSelect(hours.map(h => f"$h%02d"), hours.map(_.toString), state.hours, (v: String) => (s: State) => s.copy(hours = v.toInt))),
                    <.div(^.className := "col-sm-1 no-gutters", drawSelect(minutes.map(m => f"$m%02d"), minutes.map(_.toString), state.minutes, (v: String) => (s: State) => s.copy(minutes = v.toInt))),
                    <.div(^.className := "col-sm-1 no-gutters", <.input.button(^.value := "Go", ^.disabled := !isValidSnapshotDate, ^.className := "btn btn-primary", ^.onClick ==> selectPointInTime)),
                    errorMessage
                  ).toTagMod
                )))
          } else {
            <.div(^.className := "form-group row",
              <.div(s"Showing Snapshot at: ${state.snapshotDateTime.prettyDateTime()}", ^.className := "popover-trigger",
                ^.onClick ==> ((e: ReactEventFromInput) => scope.modState(_.copy(showDatePicker = true))))
            )
          }))
    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}
