package drt.client.components

import drt.client.actions.Actions.{SetPointInTime, SetPointInTimeToLive}
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.SDateLike
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}

import scala.scalajs.js.Date

object DatePickerComponent {

  case class Props(dateSelected: Option[SDateLike], terminalName: String)

  case class State(showDatePicker: Boolean, day: Int, month: Int, year: Int, hours: Int, minutes: Int) {
    def selectedDateTime = SDate(year, month, day, hours, minutes)
  }

  val today = new Date()
  val initialState = State(false, today.getDate(), today.getMonth(), today.getFullYear(), today.getHours(), today.getMinutes())

  def formRow(label: String, xs: TagMod*) = {
    <.div(^.className := "form-group row",
      <.label(label, ^.className := "col-sm-1 col-form-label"),
      <.div(^.className := "col-sm-8", xs.toTagMod))
  }

  implicit val propsReuse: Reusability[Props] = Reusability.by(_.dateSelected.map(_.millisSinceEpoch))
  implicit val stateReuse: Reusability[State] = Reusability.by(s => (s.day, s.month, s.year, s.hours, s.minutes).hashCode())

  val component = ScalaComponent.builder[Props]("DatePicker")
    .initialStateFromProps(
      p => {
        p.dateSelected match {
          case Some(ds) =>
            State(false, ds.getDate(), ds.getMonth(), ds.getFullYear(), 23, 59)
          case None =>
            State(false, today.getDate(), today.getMonth(), today.getFullYear(), today.getHours(), today.getMinutes())
        }
      }
    )
    .renderPS((scope, props, state) => {
      val months = Seq("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December").zip(1 to 12)
      val days = Seq.range(1, 31)
      val years = Seq.range(2017, today.getFullYear() + 1)
      val hours = Seq.range(0, 24)

      val minutes = Seq.range(0, 60)

      def drawSelect(names: Seq[String], values: Seq[String], defaultValue: Int, callback: (String) => (State) => State) = {
        <.select(^.className := "form-control", ^.defaultValue := defaultValue.toString, ^.value := defaultValue.toString,
          ^.onChange ==> ((e: ReactEventFromInput) => scope.modState(callback(e.target.value))),
          values.zip(names).map {
            case (name, value) => <.option(^.value := value, name)
          }.toTagMod)
      }

      def daysInMonth(month: Int, year: Int) = new Date(year, month, 0).getDate()

      def selectPointInTime = (e: ReactEventFromInput) => {
        SPACircuit.dispatch(SetPointInTime(state.selectedDateTime.millisSinceEpoch))
        scope.modState(_.copy(showDatePicker = false))
      }

      def selectYesterday = (e: ReactEventFromInput) => {
        val yesterday = SDate.midnightThisMorning().addMinutes(-1)
        SPACircuit.dispatch(SetPointInTime(yesterday.millisSinceEpoch))
        scope.modState(_.copy(true, yesterday.getDate(), yesterday.getMonth(), yesterday.getFullYear(), yesterday.getHours(), yesterday.getMinutes()))
      }

      def selectTomorrow = (e: ReactEventFromInput) => {
        val tomorrow = SDate.midnightThisMorning().addDays(2).addMinutes(-1)
        SPACircuit.dispatch(SetPointInTime(tomorrow.millisSinceEpoch))
        scope.modState(_.copy(true, tomorrow.getDate(), tomorrow.getMonth(), tomorrow.getFullYear(), tomorrow.getHours(), tomorrow.getMinutes()))
      }

      def selectToday = (e: ReactEventFromInput) => {
        val now = SDate.now()
        SPACircuit.dispatch(SetPointInTimeToLive())
        scope.modState(_.copy(true, now.getDate(), now.getMonth(), now.getFullYear(), now.getHours(), now.getMinutes()))
      }

      val yesterdayActive = if (state.selectedDateTime.ddMMyyString == SDate.now().addDays(-1).ddMMyyString) "active" else ""
      val todayActive = if (state.selectedDateTime.ddMMyyString == SDate.now().ddMMyyString) "active" else ""
      val tomorrowActive = if (state.selectedDateTime.ddMMyyString == SDate.now().addDays(1).ddMMyyString) "active" else ""

      val errorMessage = if (!SnapshotSelector.isLaterThanEarliest(state.selectedDateTime)) <.div(^.className := "error-message", s"Earliest available is ${SnapshotSelector.earliestAvailable.ddMMyyString}") else <.div()

      def isDataAvailableForDate = SnapshotSelector.isLaterThanEarliest(state.selectedDateTime)

      <.div(^.className := "date-selector",
        <.div(^.className := "form-group row",
          <.div(^.className := "btn-group col-sm-4 no-gutters", VdomAttr("data-toggle") := "buttons",
            <.div(^.className := s"btn btn-primary $yesterdayActive", "Yesterday", ^.onClick ==> selectYesterday),
            <.div(^.className := s"btn btn-primary $todayActive", "Today", ^.onClick ==> selectToday),
            <.div(^.className := s"btn btn-primary $tomorrowActive", "Tomorrow", ^.onClick ==> selectTomorrow)),
          <.div(
            <.label(^.className := "col-sm-1 no-gutters text-center", "or"),
            List(
              <.div(^.className := "col-sm-1 no-gutters", drawSelect(List.range(1, daysInMonth(state.month, state.year) + 1).map(_.toString), days.map(_.toString), state.day, (v: String) => (s: State) => s.copy(day = v.toInt))),
              <.div(^.className := "col-sm-2 no-gutters", drawSelect(months.map(_._2.toString), months.map(_._1.toString), state.month, (v: String) => (s: State) => s.copy(month = v.toInt))),
              <.div(^.className := "col-sm-1 no-gutters", drawSelect(years.map(_.toString), years.map(_.toString), state.year, (v: String) => (s: State) => s.copy(year = v.toInt))),
              <.input.button(^.value := "Go", ^.className := "btn btn-primary", ^.onClick ==> selectPointInTime, ^.disabled := !isDataAvailableForDate),
              errorMessage
            ).toTagMod))
      )
    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}
