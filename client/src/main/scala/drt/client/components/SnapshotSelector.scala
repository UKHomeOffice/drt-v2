package drt.client.components

import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlDateParameter}
import drt.client.components.styles.{DefaultFormFieldsStyle, WithScalaCssImplicits}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.LoadingState
import drt.shared.SDateLike
import io.kinoplan.scalajs.react.material.ui.core.{MuiFormLabel, MuiGrid, MuiTextField}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CallbackTo, ReactEventFromInput, Reusability, ScalaComponent}
import org.scalajs.dom.html.Div

object SnapshotSelector extends WithScalaCssImplicits {

  val earliestAvailable = SDate(2017, 11, 2)

  val log: Logger = LoggerFactory.getLogger("SnapshotSelector")

  val months = Seq("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December").zip(1 to 12)

  case class Props(router: RouterCtl[Loc],
                   terminalPageTab: TerminalPageTabLoc,
                   loadingState: LoadingState
                  )

  case class State(dateTime: SDateLike) {
    def update(dt: SDateLike): State = copy(dateTime = dt)

    def selectedDate: SDateLike = dateTime
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
            State(p.terminalPageTab.dateFromUrlOrNow)
          case None =>
            State(today)
        }
    )
    .renderPS((scope, props, state) => {
      val selectedDate: SDateLike = props.terminalPageTab.dateFromUrlOrNow

      def isValidSnapshotDate(selected: SDateLike) = isInPast(selected)

      def isCurrentSelection = selectedDate.toISOString() == state.selectedDate.toISOString()

      def updateState(e: ReactEventFromInput): Callback = {
        e.persist()
        SDate.parse(e.target.value) match {
          case Some(d) =>
            println(s"value: ${d.toISOString()}")
            scope.modState(_.update(d))
          case _ => Callback.empty
        }
      }

      def selectPointInTime(date: SDateLike): CallbackTo[Unit] =
        if (isValidSnapshotDate(date)) {
          GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Snapshot", state.selectedDate.toISOString())
          log.info(s"state.snapshotDateTime: ${state.selectedDate.toLocalDateTimeString()}")
          props.router.set(props.terminalPageTab.withUrlParameters(UrlDateParameter(Option(state.selectedDate.toISOString()))))
        }
        else
          CallbackTo(GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Snapshot", "Invalid Date"))

      def goButton(loading: Boolean, isCurrentSelection: Boolean) = (loading, isCurrentSelection) match {
        case (true, true) =>
          <.div(^.id := "snapshot-done", Icon.spinner)
        case (false, true) =>
          <.div(^.id := "snapshot-done", Icon.checkCircleO)
        case _ =>
          <.div(<.input.button(
            ^.value := "Go",
            ^.disabled := !isValidSnapshotDate(state.selectedDate),
            ^.className := "btn btn-primary",
            ^.onClick --> selectPointInTime(state.selectedDate))
          )
      }

      def errorMessage = if (!isInPast(state.selectedDate))
        <.div(^.className := "error-message", "Please select a date in the past")
      else <.div()

      def isInPast(selectedDateTime: SDateLike) = selectedDateTime < SDate.now()

      MuiGrid(container = true, spacing = MuiGrid.Spacing.`0`)(^.className := "date-selector",
        DefaultFormFieldsStyle.snapshotSelector,
        MuiGrid(item = true, xs = 2)(
          DefaultFormFieldsStyle.datePickerLabel,
          MuiFormLabel()(
            "Select a point in time"
          ),
        ),
        MuiGrid(item = true, xs = 3)(
          MuiTextField()(
            DefaultFormFieldsStyle.dateTimePicker,
            ^.`type` := "datetime-local",
            ^.defaultValue := s"${state.selectedDate.toISODateOnly}T${state.selectedDate.prettyTime()}",
            ^.onChange ==> updateState
          )),
        MuiGrid(item = true, xs = 1)(
          DefaultFormFieldsStyle.goButton,
          goButton(props.loadingState.isLoading, isCurrentSelection),
        ),
        MuiGrid(item = true, xs = 2)(errorMessage),
        MuiGrid(item = true, xs = 12)(TimeRangeFilter(TimeRangeFilter.Props(props.router, props.terminalPageTab, WholeDayWindow(), showNow = false)))
      )


    }

    )
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(router: RouterCtl[Loc], page: TerminalPageTabLoc, loadingState: LoadingState): VdomElement = component(Props(router, page, loadingState))
}
