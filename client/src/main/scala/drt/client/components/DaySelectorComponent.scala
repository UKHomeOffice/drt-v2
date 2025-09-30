package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain._
import drt.client.components.MultiDayExportComponent.today.getLocalLastMidnight
import drt.client.components.styles.{DrtReactTheme, ILocalDateProvider, LocalDateProvider}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{LoadingState, ViewDay}
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.{RouterCtl, SetRouteVia}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, Reusability, ScalaComponent}
import scalacss.ScalaCssReactImplicits
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.scalajs.js
import scala.util.Try

case class SearchForm(displayText: String, timeText: String, arrivalDate: js.Date, fromTime: js.Date, toTime: js.Date)

object DaySelectorComponent extends ScalaCssReactImplicits {

  val extractHour: String => Int = hhmm => hhmm.split(":").headOption.flatMap(s => Try(s.toInt).toOption).getOrElse(0)
  val extractDay: String => Int = hhmm => hhmm.split("\\+").drop(1).headOption.flatMap(s => Try(s.toInt).toOption).getOrElse(0)

  def searchForm(selectedDate: SDateLike, terminalPageTab: TerminalPageTabLoc): SearchForm = {

    val isYesterday = selectedDate.ddMMyyString == SDate.now().addDays(-1).ddMMyyString

    def isToday = selectedDate.ddMMyyString == SDate.now().ddMMyyString

    val isTomorrow = selectedDate.ddMMyyString == SDate.now().addDays(1).ddMMyyString

    def defaultTimeHHMMRangeWindow: TimeRangeHours = if (isToday) CurrentWindow() else WholeDayWindow()

    val selectedWindow = TimeRangeHours(
      terminalPageTab.timeRangeStart.getOrElse(defaultTimeHHMMRangeWindow.start),
      terminalPageTab.timeRangeEnd.getOrElse(defaultTimeHHMMRangeWindow.end)
    )

    val fromTime = selectedDate.getLocalLastMidnight.addHours(extractHour(selectedWindow.start))
    val toTime = selectedDate.getLocalLastMidnight.addHours(extractHour(selectedWindow.end)).addDays(extractDay(selectedWindow.end))

    val selectedDateJs = new scala.scalajs.js.Date(selectedDate.millisSinceEpoch)
    val dayDisplayText = if (isYesterday) "yesterday" else if (isTomorrow) "tomorrow" else if (isToday) "today" else selectedDate.`DD-Month-YYYY`
    val timeText = terminalPageTab.timeSelectString

    SearchForm(
      displayText = dayDisplayText,
      timeText = timeText.getOrElse("now"),
      arrivalDate = selectedDateJs,
      fromTime = new js.Date(fromTime.millisSinceEpoch),
      toTime = new js.Date(toTime.millisSinceEpoch),
    )
  }

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(router: RouterCtl[Loc],
                   terminalPageTab: TerminalPageTabLoc,
                   loadingState: LoadingState,
                  ) extends UseValueEq

  case class DisplayDate(date: LocalDate, timeText: String, startTime: String, endTime: String, isNotValid: Boolean)

  case class TimeMachineDate(date: SDateLike, isNotValid: Boolean)

  case class State(stateDate: DisplayDate, maybeTimeMachineDate: Option[TimeMachineDate]) {
    def selectedDate: SDateLike = SDate(stateDate.date)
  }

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)

  implicit val stateReuse: Reusability[State] = Reusability((a, b) => a.stateDate == b.stateDate &&
    a.maybeTimeMachineDate.map(_.date.millisSinceEpoch) == b.maybeTimeMachineDate.map(_.date.millisSinceEpoch))

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("DaySelectorComponent")
    .initialStateFromProps { p =>
      val viewMode = p.terminalPageTab.viewMode
      val tm = viewMode match {
        case ViewDay(_, timeMachineDate) => timeMachineDate
        case _ => None
      }

      val currentWindow = CurrentWindow()
      State(
        DisplayDate(
          date = viewMode.localDate,
          timeText = "now",
          startTime = currentWindow.start,
          endTime = currentWindow.end,
          isNotValid = false
        ),
        tm.map(t => TimeMachineDate(t, isNotValid = false))
      )
    }
    .renderPS { (scope, props, state) =>

      def tmDateIsChanged: Boolean =
        (state.maybeTimeMachineDate.map(_.date), props.terminalPageTab.maybeTimeMachineDate) match {
          case (Some(_), None) => true
          case (None, Some(_)) => true
          case (Some(newTm), Some(oldTm)) => newTm.millisSinceEpoch != oldTm.millisSinceEpoch
        }

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

      def updateUrlWithDate(s: PaxSearchFormPayload, tmDate: Option[TimeMachineDate], terminalPageTab: TerminalPageTabLoc) = {
        val viewDate = SDate(s.arrivalDate.valueOf().toLong)

        val dateMonth = viewDate.getMonth
        val dateDay = viewDate.getDate
        val dateString = f"${viewDate.getFullYear}-$dateMonth%02d-$dateDay%02d"

        def startTimeFormat = SDate(s.fromDate.valueOf().toLong).toHoursAndMinutes

        def endTimeFormat = {
          val days = if (s.fromDate.getDate() != s.toDate.getDate()) Option(" +1") else None
          SDate(s.toDate.valueOf().toLong).toHoursAndMinutes + days.getOrElse("")
        }

        val selectedWindow: TimeRangeHours = s.time match {
          case "now" =>
            GoogleEventTracker.sendEvent(terminalPageTab.terminalName, "Time Range", "now")
            CurrentWindow()

          case "24hour" =>
            GoogleEventTracker.sendEvent(terminalPageTab.terminalName, "Time Range", "24 hours")
            WholeDayWindow()

          case "range" =>
            GoogleEventTracker.sendEvent(terminalPageTab.terminalName, "Time Range", "range")
            TimeRangeHours(
              startTimeFormat,
              endTimeFormat,
            )

          case _ => CurrentWindow()
        }

        val params = List(
          UrlDateParameter(Option(dateString)),
          UrlTimeRangeStart(Option(selectedWindow.start)),
          UrlTimeRangeEnd(Option(selectedWindow.end)),
          UrlTimeMachineDateParameter(tmDate.map(_.date.toISOString)),
          UrlTimeSelectedParameter(Option(s.time))
        )

        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Time Range", "24 hours")
        props.router.set(
          props.terminalPageTab.withUrlParameters(params: _*)
        )
      }

      def updateUrlWithDateCallback(date: Option[SDateLike], tmDate: Option[TimeMachineDate]): Callback = {
        val params = List(
          UrlDateParameter(date.map(_.toISODateOnly)),
          UrlTimeRangeStart(None),
          UrlTimeRangeEnd(None),
          UrlTimeMachineDateParameter(tmDate.map(_.date.toISOString)),
        )

        props.router.set(
          props.terminalPageTab.withUrlParameters(params: _*)
        )
      }

      def goButton(loading: Boolean, dateIsUpdated: Boolean): TagMod = (loading, dateIsUpdated) match {
        case (false, false) =>
          <.div(^.className := "time-machine-action", Icon.checkCircleO)
        case (true, _) =>
          <.div(^.className := "time-machine-action", MuiCircularProgress()())
        case (_, true) =>
          <.div(^.className := "time-machine-action", <.div(Icon.arrowRight, ^.className := s"btn btn-primary", ^.onClick ==> loadTimeMachineDate))
      }

      val searchFormForDate = searchForm(state.selectedDate, props.terminalPageTab)

      <.div(^.className := s"flex-horz-between",
        ThemeProvider(DrtReactTheme)(
          <.div(^.className := s"arrival-datetime-pax-search",
            LocalDateProvider(ILocalDateProvider(
              PaxSearchFormComponent(
                IPaxSearchForm(
                  day = searchFormForDate.displayText,
                  time = searchFormForDate.timeText,
                  arrivalDate = searchFormForDate.arrivalDate,
                  fromDate = searchFormForDate.fromTime,
                  toDate = searchFormForDate.toTime,
                  timeMachine = state.maybeTimeMachineDate.nonEmpty,
                  onChange = (s: PaxSearchFormPayload) => {
                    val tm = if (s.timeMachine) {
                      state.maybeTimeMachineDate match {
                        case Some(tm) => Some(tm)
                        case None => Some(TimeMachineDate(state.selectedDate, isNotValid = false))
                      }
                    } else None
                    updateUrlWithDate(s, tm, props.terminalPageTab).runNow()
                  },
                  key = "pax-search-form",
                )
              ))
            ))
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
