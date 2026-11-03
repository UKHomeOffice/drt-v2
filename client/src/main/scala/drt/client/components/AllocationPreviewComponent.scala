package drt.client.components

import diode.UseValueEq
import diode.data.{ Pending, Pot, Ready }
import drt.client.actions.AllocationPreviewActions._
import drt.client.components.govuk.{ Button, ButtonProps }
import drt.client.components.TerminalDesksAndQueues.{
  Deployments,
  DisplayType,
  FrozenPreview,
  Hourly,
  PreviewOverrides,
  Props => DeskProps,
  Recommended
}
import drt.client.services.AllocationPreviewState.Context
import drt.client.services.{ AllocationPreviewData, AllocationPreviewState, SPACircuit }
import io.kinoplan.scalajs.react.material.ui.core.MuiSwitch
import japgolly.scalajs.react.{
  BackendScope,
  Callback,
  CtorType,
  ReactEvent,
  ReactEventFromInput,
  Reusability,
  ScalaComponent
}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import scala.scalajs.js

object AllocationPreviewComponent {
  case class Props(saved: DeskProps, preview: AllocationPreviewState, portAccess: Boolean) extends UseValueEq
  case class State(interval: Int, displayType: DisplayType)

  def context(props: Props): Context = Context(
    props.saved.terminal,
    props.saved.viewMode.localDate,
    AllocationPreviewState.eligible(
      props.saved.featureFlags.enableQueueAllocationPreview,
      props.saved.loggedInUser,
      props.portAccess,
      props.saved.viewMode.maybePointInTime.nonEmpty
    ),
    ownerId = props.saved.loggedInUser.id
  )

  def active(props: Props): Boolean =
    props.preview.active && props.preview.context.contains(context(props)) && context(props).eligible

  private def savedKind(props: Props): String =
    if (props.saved.terminalPageTab.deskType == Deployments) "available" else "recommended"

  def measurementProps(props: Props): Option[AllocationMeasurementsComponent.Props] = for {
    ctx <- props.preview.context if ctx == context(props) && ctx.eligible
    response <- props.preview.response
    kind = if (active(props)) props.preview.selectedKind else savedKind(props)
    result <- props.preview.resultFor(kind)
  } yield AllocationMeasurementsComponent.Props(
    ctx.terminal,
    ctx.date,
    kind,
    response.startMillis,
    response.endMillis,
    result
  )

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) =>
    active(a) && active(b) && context(a) == context(b) && a.preview == b.preview &&
      a.saved.viewStart.millisSinceEpoch == b.saved.viewStart.millisSinceEpoch &&
      a.saved.hoursToView == b.saved.hoursToView &&
      a.saved.terminalPageTab.displayAs == b.saved.terminalPageTab.displayAs &&
      a.saved.featureFlags.displayWaitTimesToggle == b.saved.featureFlags.displayWaitTimesToggle
  )
  implicit val stateReuse: Reusability[State] =
    Reusability.by(s => (s.interval, s.displayType.queryParamsValue))

  private def previewPot[A](value: Option[A]): Pot[A] = value.map(v => Ready(v): Pot[A]).getOrElse(Pending())

  def previewProps(
      props: Props,
      local: State,
      onInterval: TerminalDesksAndQueues.TimeInterval => Callback,
      onDisplay: DisplayType => Callback
  ): DeskProps = {
    val state = props.preview
    val data = AllocationPreviewData.aggregate(
      state,
      props.saved.terminal,
      props.saved.viewStart,
      props.saved.hoursToView,
      local.interval,
      available = state.selectedKind == "available"
    )
    val result = state.response.flatMap(AllocationPreviewData.selectedResult(_, state.selectedKind == "available"))
    props.saved.copy(
      showActuals = false,
      windowCrunchSummaries = previewPot(data.map(_.windowCrunch.toMap)),
      windowStaffRecs = previewPot(data.map(_.recommendedStaff.toMap)),
      windowStaffDeps = previewPot(data.map(_.availableStaff.toMap)),
      dayCrunchSummaries = previewPot(data.map(_.dayCrunch.toMap)),
      windowStaffSummaries = previewPot(data.map(_.staff)),
      addedStaffMovementMinutes = Map.empty,
      userPreferences = props.saved.userPreferences.copy(desksAndQueuesIntervalMinutes = local.interval),
      previewOverrides = Some(PreviewOverrides(
        preview = FrozenPreview(
          result.map(_.queues).getOrElse(Seq.empty),
          result.map(_.slas).getOrElse(Map.empty)
        ),
        onKind = kind =>
          Callback(SPACircuit.dispatch(
            SelectNeutralAllocation(neutral = true, kind = if (kind == Deployments) "available" else "recommended")
          )),
        onInterval = onInterval,
        onDisplay = onDisplay,
        deskType = if (state.selectedKind == "available") Deployments else Recommended,
        displayType = local.displayType,
        slots = data.map(_.windowCrunch.keys.toSeq).getOrElse(Seq.empty)
      ))
    )
  }

  class Backend($ : BackendScope[Props, State]) {
    def syncContext: Callback = $.props.flatMap { p =>
      Callback(SPACircuit.dispatch(SetPreviewContext(context(p)))) >>
        (if (!active(p)) Callback(SPACircuit.dispatch(PrepareNeutralAllocation(savedKind(p)))) else Callback.empty)
    }

    def render(props: Props, local: State): VdomElement = {
      val neutral = active(props)
      val kind = if (neutral) props.preview.selectedKind else savedKind(props)
      val current = props.preview.context.contains(context(props))
      val ready = current && props.preview.resultFor(kind).nonEmpty
      val error = if (current && !ready && !props.preview.pending) props.preview.error.orElse(
        Option.when(props.preview.attemptedKinds.contains(kind))("Neutral preparation failed. Please retry.")
      )
      else None
      val controls = if (!context(props).eligible) EmptyVdom
      else <.div(
        ^.className := "allocation-mode-controls",
        <.span("Legacy"),
        MuiSwitch(
          checked = neutral,
          inputProps = js.Dynamic.literal(
            "name" -> "allocation-mode",
            "aria-label" -> "Use Neutral allocation",
            "role" -> "switch"
          )
        )(
          ^.disabled := (!neutral && !ready),
          ^.onChange ==>
            ((event: ReactEventFromInput) => {
              val enabled = event.target.checked
              val initialise = if (!neutral && enabled)
                $.setState(State(
                  props.saved.userPreferences.desksAndQueuesIntervalMinutes,
                  props.saved.terminalPageTab.displayAs
                ))
              else Callback.empty
              initialise >> Callback(SPACircuit.dispatch(SelectNeutralAllocation(
                enabled,
                savedKind(props)
              )))
            })
        ),
        <.span("Neutral"),
        <.span(^.role := "status", if (ready) "Ready" else if (error.isEmpty) "Preparing..." else ""),
        error.map(message => <.span(^.role := "alert", message)).whenDefined,
        if (error.nonEmpty) Button(ButtonProps(
          children = "Retry",
          variant = "secondary",
          size = "default",
          className = "allocation-mode-retry",
          onClick = ((_: ReactEvent) =>
            Callback(SPACircuit.dispatch(RetryNeutralAllocation)).runNow()): js.Function1[ReactEvent, Unit]
        ))
        else EmptyVdom
      )
      val content = if (!neutral) TerminalDesksAndQueues(props.saved)
      else if (!ready) {
        if (props.preview.pending) <.p(^.role := "status", "Calculating Neutral allocation...") else EmptyVdom
      } else TerminalDesksAndQueues(previewProps(
        props,
        local,
        interval =>
          $.modState(_.copy(interval = if (interval == Hourly) 60 else 15)),
        display => $.modState(_.copy(displayType = display))
      ))
      <.div(
        controls,
        <.div(measurementProps(props).map(AllocationMeasurementsComponent(_)).whenDefined),
        content
      )
    }
  }

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("AllocationPreview")
    .initialStateFromProps(p =>
      State(
        p.saved.userPreferences.desksAndQueuesIntervalMinutes,
        p.saved.terminalPageTab.displayAs
      )
    )
    .renderBackend[Backend]
    .componentDidMount(_.backend.syncContext)
    .componentDidUpdate(_.backend.syncContext)
    .componentWillUnmount(_ => Callback(SPACircuit.dispatch(ExitAllocationPreview)))
    .configure(Reusability.shouldComponentUpdate)
    .build

  private val connected = SPACircuit.connect(m => (m.allocationPreview, m.userHasPortAccess.getOrElse(false)))

  def apply(saved: DeskProps): VdomElement =
    connected(proxy => {
      val (preview, portAccess) = proxy()
      component(Props(saved, preview, portAccess))
    })
}
