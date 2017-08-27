package drt.client.components

import diode.react.ModelProxy
import drt.client.actions.Actions.{SetPointInTime, SetPointInTimeToLive}
import drt.client.logger.LoggerFactory
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.SDateLike
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}

import scala.scalajs.js.Date

object DateTimeSelector {

  val log = LoggerFactory.getLogger("DateTimeSelector")

  case class Props()

  case class State(live: Boolean, showDatePicker: Boolean, day: Int, month: Int, year: Int, hours: Int, minutes: Int) {
    def snapshotDateTime = SDate(year, month + 1, day, hours, minutes)
  }


  val today = new Date()
  val initialState = State(true, false, today.getDate(), today.getMonth(), today.getFullYear(), today.getHours(), today.getMinutes())

  def formRow(label: String, xs: TagMod*) = {
    <.div(^.className := "form-group row",
      <.label(label, ^.className := "col-sm-4 col-form-label"),
      <.div(^.className := "col-sm-8", xs.toTagMod))
  }

  val component = ScalaComponent.builder[Props]("DateTimeSelector")
    .initialState(initialState).renderS((scope, state) => {
    val pointInTimeRCP = SPACircuit.connect(
      m => m.pointInTime
    )
    pointInTimeRCP((pointInTimeMP: ModelProxy[Option[SDateLike]]) => {

      val pointInTime = pointInTimeMP()
      val months = Seq("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December").zipWithIndex
      val days = Seq.range(1, 31)
      val years = Seq.range(2017, today.getFullYear() + 1)
      val hours = Seq.range(0, 24)

      val minutes = Seq.range(0, 60)

      def drawSelect(values: Seq[String], names: Seq[String], defaultValue: Int, callback: (String) => (State) => State) = {
        log.info(s"drawSelect: $state")
        val nameValues = values.zip(names)
        <.select(^.defaultValue := defaultValue.toString,
          ^.onChange ==> ((e: ReactEventFromInput) => scope.modState(callback(e.target.value))),
          nameValues.map {
            case (name, value) => <.option(^.value := value, name)
          }.toTagMod)
      }

      def daysInMonth(month: Int, year: Int) = new Date(year, month, 0).getDate()

      def selectPointInTime = (e: ReactEventFromInput) => {
        SPACircuit.dispatch(SetPointInTime(state.snapshotDateTime.toISOString()))
        scope.modState(_.copy(live = false, showDatePicker = false))
      }

      def backToLive = (e: ReactEventFromInput) => {
        SPACircuit.dispatch(SetPointInTimeToLive())
        scope.modState(_.copy(live = true, showDatePicker = false))
      }

      <.div(
        <.div(^.className := "date-time-picker-container",

          <.div(
            if (state.showDatePicker) {
              <.div(
                <.div(^.className := "popover-overlay"),
                <.div(^.className := "container",

                  formRow("Choose Date: ", <.div(^.className := "date-select", List(
                    drawSelect(months.map(_._1.toString), months.map(_._2.toString), state.month, (v: String) => (s: State) => s.copy(month = v.toInt)),
                    drawSelect(List.range(1, daysInMonth(state.month, state.year) + 1).map(_.toString), days.map(_.toString), state.day, (v: String) => (s: State) => s.copy(day = v.toInt)),
                    drawSelect(years.map(_.toString), years.map(_.toString), state.day, (v: String) => (s: State) => s.copy(year = v.toInt))
                  ).toTagMod)),
                  formRow("Choose Time: ",
                    <.div(^.className := "time-select", List(
                      drawSelect(hours.map(h => f"$h%02d"), hours.map(_.toString), state.hours, (v: String) => (s: State) => s.copy(hours = v.toInt)),
                      drawSelect(minutes.map(m => f"$m%02d"), minutes.map(_.toString),  state.minutes, (v: String) => (s: State) => s.copy(minutes = v.toInt))
                    ).toTagMod)),
                  <.input.button(^.value := "Load snapshot", ^.className:="btn btn-success", ^.onClick ==> selectPointInTime),
                  <.input.button(^.value := "Back to live", ^.className:="btn btn-secondary", ^.onClick ==> backToLive)))
            } else "",
            if (!scope.state.live) {
              <.div(s"Showing Snapshot at: ${state.snapshotDateTime.toLocalDateTimeString()}", ^.className := "popover-trigger", ^.onClick ==> ((e: ReactEventFromInput) => scope.modState(_.copy(showDatePicker = true))))

            } else {
              <.div(^.onClick ==> ((e: ReactEventFromInput) => scope.modState(_.copy(showDatePicker = true))), ^.className := "popover-trigger", "Show Snapshot")
            }
          )
        )
      )
    })
  }).build


  def apply(): VdomElement = component(Props())
}
