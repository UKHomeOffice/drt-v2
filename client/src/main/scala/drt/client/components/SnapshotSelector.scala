package drt.client.components

import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlDateParameter}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.LoadingState
import drt.shared.SDateLike
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}
import org.scalajs.dom.html.Div

import scala.scalajs.js.Date

object SnapshotSelector {

  val earliestAvailable = SDate(2017, 11, 2)

  val log: Logger = LoggerFactory.getLogger("SnapshotSelector")

  val months = Seq("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December").zip(1 to 12)

  case class Props(router: RouterCtl[Loc],
                   terminalPageTab: TerminalPageTabLoc,
                   loadingState: LoadingState
                  )

  case class State(showDatePicker: Boolean, day: Int, month: Int, year: Int, hours: Int, minutes: Int) {
    def snapshotDateTime = SDate(year, month, day, hours, minutes)
  }

  val today: SDateLike = SDate.now()

  def formRow(label: String, xs: TagMod*): TagOf[Div] = {
    <.div(^.className := "form-group row",
      <.label(label, ^.className := "col-sm-1 col-form-label"),
      <.div(^.className := "col-sm-8", xs.toTagMod))
  }

  def isLaterThanEarliest(dateTime: SDateLike): Boolean = {
    dateTime.millisSinceEpoch > earliestAvailable.millisSinceEpoch
  }

  implicit val stateReuse: Reusability[State] = Reusability.by(_.hashCode())
  implicit val propsReuse: Reusability[Props] = Reusability.by(p => p.loadingState.isLoading)

  val component = ScalaComponent.builder[Props]("SnapshotSelector")
    .initialStateFromProps(
      p =>
        p.terminalPageTab.date match {
          case Some(_) =>
            State(showDatePicker = false,
              p.terminalPageTab.dateFromUrlOrNow.getDate(),
              p.terminalPageTab.dateFromUrlOrNow.getMonth(),
              p.terminalPageTab.dateFromUrlOrNow.getFullYear(),
              p.terminalPageTab.dateFromUrlOrNow.getHours(),
              p.terminalPageTab.dateFromUrlOrNow.getMinutes()
            )
          case None =>
            State(showDatePicker = true, today.getDate(), today.getMonth(), today.getFullYear(), 0, 0)
        }
    )
    .renderPS((scope, props, state) => {
      val selectedDate: SDateLike = props.terminalPageTab.dateFromUrlOrNow

      val days = Seq.range(1, 32)
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

      def isCurrentSelection = selectedDate.toISOString() == state.snapshotDateTime.toISOString()

      def goButton(loading: Boolean, isCurrentSelection: Boolean) = (loading, isCurrentSelection) match {
        case (true, true) =>
          <.div(^.className := "col-sm-1 no-gutters", ^.id := "snapshot-done", Icon.spinner)
        case (false, true) =>
          <.div (^.className := "col-sm-1 no-gutters", ^.id := "snapshot-done", Icon.checkCircleO)
        case _ =>
          <.div(^.className := "col-sm-1 no-gutters", <.input.button(^.value := "Go", ^.disabled := !isValidSnapshotDate, ^.className := "btn btn-primary", ^.onClick ==> selectPointInTime))
      }

      def selectPointInTime = (_: ReactEventFromInput) => {
        if (isValidSnapshotDate) {
          GoogleEventTracker.sendEvent(props.terminalPageTab.terminal, "Snapshot", state.snapshotDateTime.toLocalDateTimeString())
          log.info(s"state.snapshotDateTime: ${state.snapshotDateTime.toLocalDateTimeString()}")
          props.router.set(props.terminalPageTab.withUrlParameters(UrlDateParameter(Option(state.snapshotDateTime.toLocalDateTimeString()))))
        } else {
          GoogleEventTracker.sendEvent(props.terminalPageTab.terminal, "Snapshot", "Invalid Date")
          scope.modState(_.copy(showDatePicker = true))
        }
      }

      def errorMessage = if (!isInPast)
        <.div(^.className := "error-message", "Please select a date in the past")
      else if (!isLaterThanEarliest(state.snapshotDateTime))
        <.div(^.className := "error-message", s"Earliest available is ${SnapshotSelector.earliestAvailable.ddMMyyString}")
      else <.div()

      def isInPast = state.snapshotDateTime.millisSinceEpoch < SDate.now().millisSinceEpoch

      <.div(^.className := "date-selector",
        <.div(^.className := "row",
          List(
            <.div(^.className := "col-sm-1 no-gutters", <.label("As of", ^.className := "text")),
            <.div(^.className := "col-sm-1 no-gutters narrower", drawSelect(List.range(1, daysInMonth(state.month, state.year) + 1).map(_.toString), days.map(_.toString), state.day, (v: String) => (s: State) => s.copy(day = v.toInt))),
            <.div(^.className := "col-sm-2 no-gutters narrower", drawSelect(months.map(_._1.toString), months.map(_._2.toString), state.month, (v: String) => (s: State) => s.copy(month = v.toInt))),
            <.div(^.className := "col-sm-1 no-gutters narrower", drawSelect(years.map(_.toString), years.map(_.toString), state.year, (v: String) => (s: State) => s.copy(year = v.toInt))),
            <.div(^.className := "col-sm-1 no-gutters", <.label("at", ^.className := "text center")),
            <.div(^.className := "col-sm-1 no-gutters narrower", drawSelect(hours.map(h => f"$h%02d"), hours.map(_.toString), state.hours, (v: String) => (s: State) => s.copy(hours = v.toInt))),
            <.div(^.className := "col-sm-1 no-gutters narrower", drawSelect(minutes.map(m => f"$m%02d"), minutes.map(_.toString), state.minutes, (v: String) => (s: State) => s.copy(minutes = v.toInt))),
            <.div(^.className := "col-sm-1 no-gutters spacer", <.label(" ", ^.className := "text center")),
            goButton(props.loadingState.isLoading, isCurrentSelection),
            errorMessage
          ).toTagMod),
        TimeRangeFilter(TimeRangeFilter.Props(props.router, props.terminalPageTab, WholeDayWindow(), showNow = false))
      )
    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(router: RouterCtl[Loc], page: TerminalPageTabLoc, loadingState: LoadingState): VdomElement = component(Props(router, page, loadingState))
}
