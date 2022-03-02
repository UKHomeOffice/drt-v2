package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain._
import drt.client.components.styles.DefaultFormFieldsStyle
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.LoadingState
import drt.client.util.DateUtil.isNotValidDate
import io.kinoplan.scalajs.react.material.ui.core.{MuiGrid, MuiTextField}
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

  case class State(stateDate: StateDate) {
    def selectedDate: SDateLike = SDate(stateDate.date)

    def update(d: LocalDate): State = copy(stateDate = StateDate(date = d))

    def update(isNotValid: Boolean): State = copy(stateDate = StateDate(date = stateDate.date, isNotValid = isNotValid))
  }

  val today: SDateLike = SDate.now()

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("DatePicker")
    .initialStateFromProps { p =>
      val viewMode = p.terminalPageTab.viewMode
      val time = viewMode.time
      State(StateDate(time.toLocalDate))
    }
    .renderPS(r = (scope, props, state) => {

      def isCurrentSelection = state.selectedDate.ddMMyyString == props.terminalPageTab.dateFromUrlOrNow.ddMMyyString

      def updateState(e: ReactEventFromInput): Callback = {
        e.persist()
        isNotValidDate(e.target.value) match {
          case true =>
            scope.modState(_.update(true))
          case false =>
            scope.modState(_.update(LocalDate.parse(e.target.value).getOrElse(state.stateDate.date)))
        }
      }

      def updateUrlWithDateCallback(date: Option[SDateLike]): Callback =
        props.router.set(
          props
            .terminalPageTab
            .withUrlParameters(UrlDateParameter(date.map(_.toISODateOnly)), UrlTimeRangeStart(None), UrlTimeRangeEnd(None))
        )

      def selectPointInTime = (_: ReactEventFromInput) => {
        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Point In time", state.selectedDate.toISODateOnly)
        updateUrlWithDateCallback(Option(state.selectedDate))
      }

      def selectYesterday = (_: ReactEventFromInput) => {
        val yesterday = SDate.midnightThisMorning().addMinutes(-1)
        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Yesterday", yesterday.toISODateOnly)
        updateUrlWithDateCallback(Option(yesterday))
      }

      def selectTomorrow = (_: ReactEventFromInput) => {
        val tomorrow = SDate.midnightThisMorning().addDays(2).addMinutes(-1)
        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Tomorrow", tomorrow.toISODateOnly)
        updateUrlWithDateCallback(Option(tomorrow))
      }

      def selectToday = (_: ReactEventFromInput) => {
        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Today", "Today")
        updateUrlWithDateCallback(None)
      }

      def goButton(loading: Boolean, isCurrentSelection: Boolean, isNotValid: Boolean): TagMod = (loading, isCurrentSelection, isNotValid) match {
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

      def defaultTimeRangeWindow = if (isTodayActive)
        CurrentWindow()
      else
        WholeDayWindow()


      MuiGrid(container = true, spacing = MuiGrid.Spacing.`0`)(^.className := "date-selector",
        DefaultFormFieldsStyle.daySelector,
        MuiGrid(item = true, xs = 4)(
          <.div(^.className := "btn-group no-gutters", VdomAttr("data-toggle") := "buttons",
            <.div(^.id := "yesterday", ^.className := s"btn btn-primary $yesterdayActive", "Yesterday", ^.onClick ==> selectYesterday),
            <.div(^.id := "today", ^.className := s"btn btn-primary $todayActive", "Today", ^.onClick ==> selectToday),
            <.div(^.id := "tomorrow", ^.className := s"btn btn-primary $tomorrowActive end-spacer", "Tomorrow", ^.onClick ==> selectTomorrow)),
        ),
        MuiGrid(item = true, xs = 8)(
          MuiGrid(container = true, spacing = MuiGrid.Spacing.`0`)(
            MuiGrid(item = true, xs = 4)(
              MuiTextField()(
                DefaultFormFieldsStyle.datePicker,
                ^.`type` := "date",
                ^.defaultValue := s"${state.stateDate.date.toISOString}",
                ^.onChange ==> updateState,
              )
            ),
            MuiGrid(item = true, xs = 4)(
              DefaultFormFieldsStyle.goButton,
              goButton(props.loadingState.isLoading, isCurrentSelection, state.stateDate.isNotValid),
            )
          )
        ),
        MuiGrid(item = true, xs = 6)(
          TimeRangeFilter(
            TimeRangeFilter.Props(props.router, props.terminalPageTab, defaultTimeRangeWindow, isTodayActive, props.minuteTicker)
          )
        )
      )
    })
    .build

  def apply(props: Props): VdomElement = component(props)
}
