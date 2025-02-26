package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.SPAMain._
import drt.client.components.styles.{DrtReactTheme, DrtTheme}
import drt.client.components.styles.DrtTheme._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{LoadingState, ViewDay}
import drt.client.util.DateUtil.isNotValidDate
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.Info
import japgolly.scalajs.react.callback.CallbackTo
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.component.builder.ComponentBuilder
import japgolly.scalajs.react.extra.router.{RouterCtl, SetRouteVia}
import japgolly.scalajs.react.vdom.html_<^.{^, _}
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, Reusability, ScalaComponent}
import scalacss.ScalaCssReactImplicits
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.scalajs.js

object DaySelectorComponent extends ScalaCssReactImplicits {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(router: RouterCtl[Loc],
                   terminalPageTab: TerminalPageTabLoc,
                   loadingState: LoadingState,
                  ) extends UseValueEq

  case class DisplayDate(date: LocalDate, isNotValid: Boolean)

  case class TimeMachineDate(date: SDateLike, isNotValid: Boolean)

  case class State(stateDate: DisplayDate, maybeTimeMachineDate: Option[TimeMachineDate]) {
    def selectedDate: SDateLike = SDate(stateDate.date)

    def update(d: LocalDate): State = copy(stateDate = DisplayDate(date = d, isNotValid = false))

    def updateValidity(isNotValid: Boolean): State = copy(stateDate = DisplayDate(date = stateDate.date, isNotValid = isNotValid))
  }

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)
  implicit val stateReuse: Reusability[State] = Reusability((a, b) => a.stateDate == b.stateDate && a.maybeTimeMachineDate.map(_.date.millisSinceEpoch) == b.maybeTimeMachineDate.map(_.date.millisSinceEpoch))

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("DatePicker")
    .initialStateFromProps { p =>
      val viewMode = p.terminalPageTab.viewMode
      val tm = viewMode match {
        case ViewDay(_, timeMachineDate) => timeMachineDate
        case _ => None
      }
      State(DisplayDate(viewMode.localDate, isNotValid = false), tm.map(t => TimeMachineDate(t, isNotValid = false)))
    }
    .renderPS { (scope, props, state) =>

      def tmDateIsChanged: Boolean =
        (state.maybeTimeMachineDate.map(_.date), props.terminalPageTab.maybeTimeMachineDate) match {
          case (Some(_), None) => true
          case (None, Some(_)) => true
          case (Some(newTm), Some(oldTm)) => newTm.millisSinceEpoch != oldTm.millisSinceEpoch
        }

//      def updateDisplayDate(e: ReactEventFromInput): Callback = {
//        e.preventDefault()
//        e.persist()
//        if (isNotValidDate(e.target.value)) {
//          scope.modState(_.updateValidity(true))
//        } else {
//          val newDate = LocalDate.parse(e.target.value).getOrElse(state.stateDate.date)
//          scope.modState { s => s.update(newDate) }
//          GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Point In time", state.selectedDate.toISODateOnly)
//          updateUrlWithDateCallback(Option(SDate(newDate)), state.maybeTimeMachineDate)
//        }
//      }

      def updateTimeMachineDate(e: ReactEventFromInput): Callback = {
        e.persist()
        SDate.parse(e.target.value) match {
          case Some(d) => scope.modState(_.copy(maybeTimeMachineDate = Option(TimeMachineDate(d, isNotValid = false))))
          case _ => Callback.empty
        }
      }

      def loadTimeMachineDate(e: ReactEventFromInput): Callback = {
        e.preventDefault()
        e.persist()
        updateUrlWithDateCallback(Option(state.selectedDate), state.maybeTimeMachineDate)
      }

      def updateUrlWithDate(is24Hours: Boolean, date: Option[String], tmDate: Option[TimeMachineDate]) = {
//        println(s"is24Hours: $is24Hours")
        val params = List(
          UrlDateParameter(date),
          UrlTimeRangeStart(if (is24Hours) Option(WholeDayWindow().start.toString) else None),
          UrlTimeRangeEnd(if (is24Hours) Option(WholeDayWindow().end.toString) else None),
          UrlTimeMachineDateParameter(tmDate.map(_.date.toISOString)),
        )

        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Time Range", "24 hours")
        props.router.set(
          props.terminalPageTab.withUrlParameters(params: _*),
          SetRouteVia.WindowLocation
        )
      }

      //      def updateUrlWithDate(date: Option[String], tmDate: Option[TimeMachineDate]): Callback = {
      //        val params = List(
      //          UrlDateParameter(date),
      //          UrlTimeRangeStart(None),
      //          UrlTimeRangeEnd(None),
      //          UrlTimeMachineDateParameter(tmDate.map(_.date.toISOString)),
      //        )
      //        props.router.set(
      //          props.terminalPageTab.withUrlParameters(params: _*),
      //          SetRouteVia.WindowLocation
      //        )
      //      }

      def updateUrlWithDateCallback(date: Option[SDateLike], tmDate: Option[TimeMachineDate]): Callback = {
        val params = List(
          UrlDateParameter(date.map(_.toISODateOnly)),
          UrlTimeRangeStart(None),
          UrlTimeRangeEnd(None),
          UrlTimeMachineDateParameter(tmDate.map(_.date.toISOString)),
        )

        props.router.set(
          props.terminalPageTab.withUrlParameters(params: _*),
          SetRouteVia.WindowLocation
        )
      }

//      def selectYesterday: ReactEventFromInput => Callback = (_: ReactEventFromInput) => {
//        val yesterday = SDate.midnightThisMorning().addMinutes(-1)
//        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Yesterday", yesterday.toISODateOnly)
//        updateUrlWithDateCallback(Option(yesterday), state.maybeTimeMachineDate)
//      }
//
//      def selectTomorrow: ReactEventFromInput => Callback = (_: ReactEventFromInput) => {
//        val tomorrow = SDate.midnightThisMorning().addDays(2).addMinutes(-1)
//        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Tomorrow", tomorrow.toISODateOnly)
//        updateUrlWithDateCallback(Option(tomorrow), state.maybeTimeMachineDate)
//      }
//
//      def selectToday: ReactEventFromInput => Callback = (_: ReactEventFromInput) => {
//        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Today", "Today")
//        updateUrlWithDateCallback(None, state.maybeTimeMachineDate)
//      }
//
//      def selectLatestView: ReactEventFromInput => Callback = (_: ReactEventFromInput) => {
//        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "View as of", "Now")
//        scope.modState(_.copy(maybeTimeMachineDate = None))
//        updateUrlWithDateCallback(Option(state.selectedDate), None)
//      }
//
//      def selectTimeMachineView: ReactEventFromInput => Callback = (_: ReactEventFromInput) => {
//        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "View as of", "Time machine")
//        val tmDate = TimeMachineDate(SDate.now(), isNotValid = true)
//        scope.modState(_.copy(maybeTimeMachineDate = Option(tmDate)))
//        updateUrlWithDateCallback(Option(state.selectedDate), Option(tmDate))
//      }

      def goButton(loading: Boolean, dateIsUpdated: Boolean): TagMod = (loading, dateIsUpdated) match {
        case (false, false) =>
          <.div(^.className := "time-machine-action", Icon.checkCircleO)
        case (true, _) =>
          <.div(^.className := "time-machine-action", MuiCircularProgress()())
        case (_, true) =>
          <.div(^.className := "time-machine-action", <.div(Icon.arrowRight, ^.className := s"btn btn-primary", ^.onClick ==> loadTimeMachineDate))
      }

      val isYesterday = state.selectedDate.ddMMyyString == SDate.now().addDays(-1).ddMMyyString

      def isToday = state.selectedDate.ddMMyyString == SDate.now().ddMMyyString

      val isTomorrow = state.selectedDate.ddMMyyString == SDate.now().addDays(1).ddMMyyString

      def defaultTimeRangeWindow: TimeRangeHours = if (isToday) CurrentWindow() else WholeDayWindow()

//      val liveViewButtonTheme = if (state.maybeTimeMachineDate.isEmpty) buttonSelectedTheme else buttonTheme
//      val timeMachineViewButtonTheme = if (state.maybeTimeMachineDate.nonEmpty) buttonSelectedTheme else buttonTheme
//
//      val yesterdayButtonTheme = if (isYesterday) buttonSelectedTheme else buttonTheme
//      val todayButtonTheme = if (isToday) buttonSelectedTheme else buttonTheme
//      val tomorrowButtonTheme = if (isTomorrow) buttonSelectedTheme else buttonTheme
      val selectedWindow = TimeRangeHours(
        props.terminalPageTab.timeRangeStart.getOrElse(defaultTimeRangeWindow.start),
        props.terminalPageTab.timeRangeEnd.getOrElse(defaultTimeRangeWindow.end)
      )
      val selectedDateJs = new scala.scalajs.js.Date(state.selectedDate.millisSinceEpoch)
      val fromDateJs = new scala.scalajs.js.Date(state.selectedDate.addHours(selectedWindow.start).millisSinceEpoch)
      val toDateJs = new scala.scalajs.js.Date(state.selectedDate.addHours(selectedWindow.end).millisSinceEpoch)
      val dayDisplayText = if (isYesterday) "yesterday" else if (isTomorrow) "tomorrow" else "today"
      val timeText =
        if (isToday)
          if (props.terminalPageTab.timeRangeStart.contains(0) && props.terminalPageTab.timeRangeEnd.contains(24)) "24hour" else "now"
        else "24hour"

      <.div(^.className := s"flex-horz-between",
        ThemeProvider(DrtReactTheme)(
          PaxSearchFormComponent(
            IPaxSearchForm(
              day = dayDisplayText,
              time = timeText,
              arrivalDate = selectedDateJs,
              fromDate = fromDateJs,
              toDate = toDateJs,
              timeMachine = state.maybeTimeMachineDate.nonEmpty,
              onChange = (s: PaxSearchFormPayload) => {
                val dateString = s"${s.arrivalDate.getFullYear()}-${s.arrivalDate.getMonth() + 1}-${s.arrivalDate.getDate()}"
                log.info(s"onChange: ${s.time} ${dateString}")
                val timeMachineDate = if (s.timeMachine) {
                  Option(TimeMachineDate(SDate.now(), isNotValid = false))
                } else None
                updateUrlWithDate(s.time != null & s.time != "now", Option(dateString), timeMachineDate).runNow()
              }
            )
          )
        ),
        state.maybeTimeMachineDate match {
          case Some(tmDate) =>
            <.div(^.className := "time-machine-info",
              <.div(^.className := "not-live-banner", "You are not viewing live data"),
              <.div(^.className := "not-live-message",
                s"Show",
                <.div(^.className := "time-machine-display-date", state.stateDate.date.ddmmyyyy),
                "as it was on",
                MuiTextField(
                  InputProps = js.Dynamic.literal(
                    "style" -> js.Dictionary(
                      "fontSize" -> "18px",
                      "fontWeight" -> "bold",
                    )
                  ).asInstanceOf[js.Object]
                )(
                  ^.className := "time-machine-datetime-selector",
                  ^.`type` := "datetime-local",
                  ^.defaultValue := s"${tmDate.date.toISODateOnly}T${tmDate.date.prettyTime}",
                  ^.onChange ==> updateTimeMachineDate
                ),
                <.div(^.className := "date-go-button",
                  goButton(
                    loading = props.loadingState.isLoading,
                    dateIsUpdated = tmDateIsChanged,
                  ),
                )
              ),
            )
          case None => EmptyVdom
        },
      )
    }
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}
