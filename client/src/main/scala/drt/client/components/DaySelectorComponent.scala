package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain._
import drt.client.components.styles.DefaultFormFieldsStyle
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.LoadingState
import drt.client.util.DateUtil.isNotValidDate
import io.kinoplan.scalajs.react.material.ui.core.{MuiDivider, MuiTextField}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.{^, _}
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import scalacss.ScalaCssReactImplicits
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

object DaySelectorComponent extends ScalaCssReactImplicits {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(router: RouterCtl[Loc],
                   terminalPageTab: TerminalPageTabLoc,
                   loadingState: LoadingState,
                   minuteTicker: Int
                  ) extends UseValueEq

  case class StateDate(date: LocalDate, isNotValid: Boolean = false)

  case class State(stateDate: StateDate, maybeTimeMachineDate: Option[SDateLike]) {
    def selectedDate: SDateLike = SDate(stateDate.date)

    def update(d: LocalDate): State = copy(stateDate = StateDate(date = d))

    def updateValidity(isNotValid: Boolean): State = copy(stateDate = StateDate(date = stateDate.date, isNotValid = isNotValid))
  }

  val today: SDateLike = SDate.now()

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("DatePicker")
    .initialStateFromProps { p =>
      val viewMode = p.terminalPageTab.viewMode
      val time = viewMode.time
      State(StateDate(time.toLocalDate), None)
    }
    .renderPS(r = (scope, props, state) => {

      def isCurrentSelection = state.selectedDate.ddMMyyString == props.terminalPageTab.dateFromUrlOrNow.ddMMyyString

      def updateDisplayDate(e: ReactEventFromInput): Callback = {
        e.persist()
        if (isNotValidDate(e.target.value)) {
          scope.modState(_.updateValidity(true))
        } else {
          scope.modState(_.update(LocalDate.parse(e.target.value).getOrElse(state.stateDate.date)))
        }
      }

      def updateTimeMachineDate(e: ReactEventFromInput): Callback = {
        e.persist()
        SDate.parse(e.target.value) match {
          case Some(d) => scope.modState(_.copy(maybeTimeMachineDate = Option(d)))
          case _ => Callback.empty
        }
      }

      def updateUrlWithDateCallback(date: Option[SDateLike]): Callback =
        props.router.set(
          props
            .terminalPageTab
            .withUrlParameters(UrlDateParameter(date.map(_.toISODateOnly)), UrlTimeRangeStart(None), UrlTimeRangeEnd(None))
        )

      def selectPointInTime: ReactEventFromInput => Callback = (_: ReactEventFromInput) => {
        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Point In time", state.selectedDate.toISODateOnly)
        updateUrlWithDateCallback(Option(state.selectedDate))
      }

      def selectYesterday: ReactEventFromInput => Callback = (_: ReactEventFromInput) => {
        val yesterday = SDate.midnightThisMorning().addMinutes(-1)
        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Yesterday", yesterday.toISODateOnly)
        updateUrlWithDateCallback(Option(yesterday))
      }

      def selectTomorrow: ReactEventFromInput => Callback = (_: ReactEventFromInput) => {
        val tomorrow = SDate.midnightThisMorning().addDays(2).addMinutes(-1)
        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Tomorrow", tomorrow.toISODateOnly)
        updateUrlWithDateCallback(Option(tomorrow))
      }

      def selectToday: ReactEventFromInput => Callback = (_: ReactEventFromInput) => {
        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Today", "Today")
        updateUrlWithDateCallback(None)
      }

      def selectLatestView: ReactEventFromInput => Callback = (_: ReactEventFromInput) => {
        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "View as of", "Now")
        scope.modState(_.copy(maybeTimeMachineDate = None))
      }

      def selectTimeMachineView: ReactEventFromInput => Callback = (_: ReactEventFromInput) => {
        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "View as of", "Time machine")
        scope.modState(_.copy(maybeTimeMachineDate = Option(SDate.now())))
      }

      def goButton(loading: Boolean, isCurrentSelection: Boolean, isNotValidDate: Boolean): TagMod = (loading, isCurrentSelection, isNotValidDate) match {
        case (_, _, true) =>
          <.div(^.id := "snapshot-error", <.div("Please enter valid date"))
        case (true, true, _) =>
          <.div(^.id := "snapshot-done", Icon.spinner)
        case (false, true, _) =>
          <.div(^.id := "snapshot-done", Icon.checkCircleO)
        case _ =>
          <.div(^.id := "snapshot-done", <.input.button(^.value := "Go", ^.className := "btn btn-primary", ^.onClick ==> selectPointInTime))
      }

      val yesterdayActive = if (state.selectedDate.ddMMyyString == SDate.now().addDays(-1).ddMMyyString) "active" else ""

      def isTodayActive = state.selectedDate.ddMMyyString == SDate.now().ddMMyyString

      val todayActive = if (isTodayActive) "active" else ""

      val tomorrowActive = if (state.selectedDate.ddMMyyString == SDate.now().addDays(1).ddMMyyString) "active" else ""

      def defaultTimeRangeWindow: TimeRangeHours = if (isTodayActive) CurrentWindow() else WholeDayWindow()

      val liveViewClass = if (state.maybeTimeMachineDate.isEmpty) "active" else ""
      val timeMachineViewClass = if (state.maybeTimeMachineDate.nonEmpty) "active" else ""

      <.div(
        ^.className := "date-component-wrapper",
        <.div(
          ^.className := "date-select-wrapper",
          DefaultFormFieldsStyle.daySelector,
          <.div(^.className := "btn-group no-gutters date-quick-days date-time-buttons-container", VdomAttr("data-toggle") := "buttons",
            <.div(^.id := "yesterday", ^.className := s"btn btn-primary $yesterdayActive", "Yesterday", ^.onClick ==> selectYesterday),
            <.div(^.id := "today", ^.className := s"btn btn-primary $todayActive", "Today", ^.onClick ==> selectToday),
            <.div(^.id := "tomorrow", ^.className := s"btn btn-primary $tomorrowActive end-spacer", "Tomorrow", ^.onClick ==> selectTomorrow)
          ),
          <.div(
            ^.className := "date-picker",
            MuiTextField()(
              ^.width := "100%",
              DefaultFormFieldsStyle.datePicker,
              ^.`type` := "date",
              ^.defaultValue := s"${state.stateDate.date.toISOString}",
              ^.onChange ==> updateDisplayDate,
            ),
            <.div(^.className := "date-go-button",
              goButton(props.loadingState.isLoading, isCurrentSelection, state.stateDate.isNotValid),
            )
          )
        ),
        TimeRangeFilter(
          TimeRangeFilter.Props(props.router, props.terminalPageTab, defaultTimeRangeWindow, isTodayActive, props.minuteTicker)
        ),
        MuiDivider()(),
        <.div(^.className := "time-machine",
          <.div(^.className := "time-machine-switch",
            <.div("Time machine", ^.className := "time-machine-switch-label"),
            <.div(^.className := "time-machine-switch-buttons",
              <.div(^.className := s"controls-radio-wrapper",
                <.input.radio(^.checked := state.maybeTimeMachineDate.nonEmpty, ^.onChange ==> selectTimeMachineView, ^.id := "time-machine-switch-on"),
                <.label(^.`for` := "time-machine-switch-on", "On")
              ),
              <.div(^.className := s"controls-radio-wrapper",
                <.input.radio(^.checked := state.maybeTimeMachineDate.isEmpty, ^.onChange ==> selectLatestView, ^.id := "time-machine-switch-off"),
                <.label(^.`for` := "time-machine-switch-off", "Off")
              ),
            )
          ),
          //          <.div(^.className := "date-time-buttons-container",
          //            <.div(^.id := "live-view", ^.className := s"btn btn-primary $liveViewClass", s"Live view", ^.onClick ==> selectLatestView),
          //            <.div(^.id := "time-machine-view", ^.className := s"btn btn-primary $timeMachineViewClass", "Time machine view", ^.onClick ==> selectTimeMachineView),
          //          ),
          state.maybeTimeMachineDate match {
            case Some(tmDate) => <.div(^.className := "date-picker",
              MuiTextField()(
                DefaultFormFieldsStyle.dateTimePicker,
                ^.`type` := "datetime-local",
                ^.defaultValue := s"${tmDate.toISODateOnly}T${tmDate.prettyTime()}",
                ^.onChange ==> updateTimeMachineDate
              ))
            case None => EmptyVdom
          }
        ),
        state.maybeTimeMachineDate match {
          case Some(tmDate) =>
            val displayDate = f"${state.stateDate.date.day}%02d/${state.stateDate.date.month}%02d/${state.stateDate.date.year}%04d"
            <.div(^.className := "time-machine-hint",
              s"View $displayDate as DRT showed on ${tmDate.ddMMyyString} at ${tmDate.prettyTime()}"
            )
          case None => EmptyVdom
        }
      )
    })
    .build

  def apply(props: Props): VdomElement = component(props)
}
