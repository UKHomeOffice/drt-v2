package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.shared.SDateLike
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CallbackTo, ReactEventFromInput, ScalaComponent, _}
import org.scalajs.dom.html.Div

import scala.scalajs.js.Date

object DateSelector {
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(label: String, selectedDate: SDateLike, callback: SDateLike => Callback)

  case class State(day: Int, month: Int, year: Int) {
    def selectedDate = SDate(year, month, day)
  }

  val today: SDateLike = SDate.now()

  def daysInMonth(month: Int, year: Int) = new Date(year, month, 0).getDate()

  def formRow(label: String, xs: TagMod*): TagOf[Div] = {
    <.div(^.className := "form-group row",
      <.label(label, ^.className := "col-sm-1 col-form-label"),
      <.div(^.className := "col-sm-8", xs.toTagMod))
  }

  implicit val propsReuse: Reusability[Props] = Reusability.by(p => p.selectedDate.toISOString())
  implicit val stateReuse: Reusability[State] = Reusability.derive[State]

  val component = ScalaComponent.builder[Props]("DatePicker")
    .initialStateFromProps(
      p => {
        State(day = p.selectedDate.getDate(), month = p.selectedDate.getMonth(), year = p.selectedDate.getFullYear())
      }
    )
    .renderPS(r = (scope, props, state) => {
      val months = Seq("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December").zip(1 to 12)
      val days = Seq.range(1, 32)
      val years = Seq.range(2017, today.getFullYear() + 2)

      def drawSelect(names: Seq[String], values: Seq[String], defaultValue: Int, callback: (String) => (State) => State) = {
        <.select(^.className := "form-control", ^.value := defaultValue.toString,
          ^.onChange ==> ((e: ReactEventFromInput) => {

            scope.modState(callback(e.target.value), CallbackTo(scope.state.selectedDate) >>= props.callback)
          }),
          values.zip(names).map {
            case (name, value) => <.option(^.value := value, name)
          }.toTagMod)
      }

      <.div(^.className := "date",
        <.div(
          <.div(
            formRow(s"${props.label}: ",
            <.div(^.className := "day date-field", drawSelect(names = List.range(1, daysInMonth(state.month, state.year) + 1).map(_.toString), values = days.map(_.toString), defaultValue = state.day, callback = (v: String) => (s: State) => s.copy(day = v.toInt))),
            <.div(^.className := "month date-field", drawSelect(names = months.map(_._2.toString), values = months.map(_._1.toString), defaultValue = state.month, callback = (v: String) => (s: State) => s.copy(month = v.toInt))),
            <.div(^.className := "year date-field", drawSelect(names = years.map(_.toString), values = years.map(_.toString), defaultValue = state.year, callback = (v: String) => (s: State) => s.copy(year = v.toInt)))
          ))
        ))
    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(label: String, selectedDate: SDateLike, callback: SDateLike => Callback): VdomElement = component(Props(label, selectedDate, callback))
}
