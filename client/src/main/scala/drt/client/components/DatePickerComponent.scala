package drt.client.components

import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{CurrentWindow, LoadingState, TimeRangeHours}
import drt.shared.SDateLike
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom.html.Div

import scala.scalajs.js.Date

object DatePickerComponent {
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(router: RouterCtl[Loc],
                   terminalPageTab: TerminalPageTabLoc,
                   loadingState: LoadingState,
                   minuteTicker: Int
                  )

  case class State(showDatePicker: Boolean, day: Int, month: Int, year: Int, hours: Int, minutes: Int) {
    def selectedDateTime = SDate(year, month, day, hours, minutes)
  }

  val today: SDateLike = SDate.now()

  def formRow(label: String, xs: TagMod*): TagOf[Div] = {
    <.div(^.className := "form-group row",
      <.label(label, ^.className := "col-sm-1 col-form-label"),
      <.div(^.className := "col-sm-8", xs.toTagMod))
  }

  implicit val propsReuse: Reusability[Props] = Reusability.by(
    p => (p.terminalPageTab.viewMode.hashCode(), p.loadingState.isLoading, p.minuteTicker)
  )
  implicit val stateReuse: Reusability[State] = Reusability.derive[State]

  val component = ScalaComponent.builder[Props]("DatePicker")
    .initialStateFromProps(
      p => {
        log.info(s"Setting state from $p")
        val viewMode = p.terminalPageTab.viewMode
        val time = viewMode.time
        State(showDatePicker = false, day = time.getDate(), month = time.getMonth(), year = time.getFullYear(), hours = time.getHours(), minutes = time.getMinutes())
      }
    )
    .renderPS(r = (scope, props, state) => {
      val months = Seq("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December").zip(1 to 12)
      val days = Seq.range(1, 32)
      val years = Seq.range(2017, today.getFullYear() + 2)

      def drawSelect(names: Seq[String], values: Seq[String], defaultValue: Int, callback: (String) => (State) => State) = {
        <.select(^.className := "form-control", ^.value := defaultValue.toString,
          ^.onChange ==> ((e: ReactEventFromInput) => scope.modState(callback(e.target.value))),
          values.zip(names).map {
            case (name, value) => <.option(^.value := value, name)
          }.toTagMod)
      }

      def isCurrentSelection = state.selectedDateTime.ddMMyyString == props.terminalPageTab.dateFromUrlOrNow.ddMMyyString

      def daysInMonth(month: Int, year: Int) = new Date(year, month, 0).getDate()

      def updateUrlWithDateCallback(date: Option[SDateLike]): Callback = {
        props.router.set(
          props.terminalPageTab.copy(
            date = date.map(_.toLocalDateTimeString()),
            timeRangeStartString = None,
            timeRangeEndString = None
          )
        )
      }

      def selectPointInTime = (_: ReactEventFromInput) => {
        updateUrlWithDateCallback(Option(state.selectedDateTime))
      }

      def selectYesterday = (_: ReactEventFromInput) => {
        val yesterday = SDate.midnightThisMorning().addMinutes(-1)
        updateUrlWithDateCallback(Option(yesterday))
      }

      def selectTomorrow = (_: ReactEventFromInput) => {
        val tomorrow = SDate.midnightThisMorning().addDays(2).addMinutes(-1)
        updateUrlWithDateCallback(Option(tomorrow))
      }

      def selectToday = (_: ReactEventFromInput) => updateUrlWithDateCallback(None)

      def isDataAvailableForDate = SnapshotSelector.isLaterThanEarliest(state.selectedDateTime)

      def goButton(loading: Boolean, isCurrentSelection: Boolean): TagMod = (loading, isCurrentSelection) match {
        case (true, true) =>
          <.div(^.id := "snapshot-done", Icon.spinner)
        case (false, true) =>
          <.div(^.id := "snapshot-done", Icon.checkCircleO)
        case _ =>
          <.div(^.id := "snapshot-done", <.input.button(^.value := "Go", ^.className := "btn btn-primary", ^.onClick ==> selectPointInTime, ^.disabled := !isDataAvailableForDate))
      }

      val yesterdayActive = if (state.selectedDateTime.ddMMyyString == SDate.now().addDays(-1).ddMMyyString) "active" else ""

      def isTodayActive = state.selectedDateTime.ddMMyyString == SDate.now().ddMMyyString

      val todayActive = if (isTodayActive) "active" else ""

      val tomorrowActive = if (state.selectedDateTime.ddMMyyString == SDate.now().addDays(1).ddMMyyString) "active" else ""

      val errorMessage = if (!SnapshotSelector.isLaterThanEarliest(state.selectedDateTime))
        <.div(^.className := "error-message", s"Earliest available is ${SnapshotSelector.earliestAvailable.ddMMyyString}")
      else <.div()

      <.div(^.className := "date-selector",
        <.div(^.className := "",
          <.div(^.className := "btn-group no-gutters", VdomAttr("data-toggle") := "buttons",
            <.div(^.className := s"btn btn-primary $yesterdayActive", "Yesterday", ^.onClick ==> selectYesterday),
            <.div(^.className := s"btn btn-primary $todayActive", "Today", ^.onClick ==> selectToday),
            <.div(^.className := s"btn btn-primary $tomorrowActive end-spacer", "Tomorrow", ^.onClick ==> selectTomorrow)),
          drawSelect(names = List.range(1, daysInMonth(state.month, state.year) + 1).map(_.toString), values = days.map(_.toString), defaultValue = state.day, callback = (v: String) => (s: State) => s.copy(day = v.toInt)),
          drawSelect(names = months.map(_._2.toString), values = months.map(_._1.toString), defaultValue = state.month, callback = (v: String) => (s: State) => s.copy(month = v.toInt)),
          drawSelect(names = years.map(_.toString), values = years.map(_.toString), defaultValue = state.year, callback = (v: String) => (s: State) => s.copy(year = v.toInt)),
          goButton(props.loadingState.isLoading, isCurrentSelection),
          errorMessage
        ),
        TimeRangeFilter(TimeRangeFilter.Props(props.router, props.terminalPageTab, CurrentWindow(), isTodayActive, props.minuteTicker))
      )
    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}
