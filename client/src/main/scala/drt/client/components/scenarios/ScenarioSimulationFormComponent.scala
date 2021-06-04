package drt.client.components.scenarios

import drt.client.SPAMain
import drt.client.actions.Actions.GetSimulation
import drt.client.components.styles.DefaultFormFieldsStyle
import drt.client.components.styles.ScalaCssImplicits._
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.dates.LocalDate
import io.kinoplan.scalajs.react.bridge.WithPropsAndTagsMods
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.icons.{MuiIcons, MuiIconsModule}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.all.{`type`, id, onChange, onClick, value}
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom.html.Div
import scalacss.ScalaCssReactImplicits

import scala.util.Try

object ScenarioSimulationFormComponent extends ScalaCssReactImplicits {

  implicit val stateReuse: Reusability[State] = Reusability.by_==[State]
  implicit val propsReuse: Reusability[Props] = Reusability.by_==[Props]

  val steps = List("Passenger numbers", "Processing Times", "Queue SLAs", "Configure Desk Availability")

  case class State(simulationFormFields: SimulationFormFields, panelStatus: Map[String, Boolean]) {
    def isOpen(panel: String): Boolean = panelStatus.getOrElse(panel, false)

    def toggle(panel: String): State = copy(
      panelStatus = panelStatus + (panel -> !isOpen(panel))
    )

    def toggleEGateHour(hour: Int): State = copy(simulationFormFields = simulationFormFields.toggleEgateHour(hour))

    def openEgatesAllDay: State = copy(simulationFormFields = simulationFormFields.openEgatesAllDay)

    def closeEgatesAllDay: State = copy(simulationFormFields = simulationFormFields.closeEgatesAllDay)
  }

  case class Props(date: LocalDate, terminal: Terminal, airportConfig: AirportConfig)

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("SimulationFormComponent")
    .initialStateFromProps(p =>
      State(SimulationFormFields(p.terminal, p.date, p.airportConfig), Map())
    )
    .renderS {

      (scope, state) =>

        def changePassengerWeighting(e: ReactEventFromInput): Callback = {
          val maybeValue = Try(e.target.value.toDouble).toOption
          scope.setState(state.copy(simulationFormFields = state.simulationFormFields.copy(passengerWeighting = maybeValue)))
        }

        def changeBankSize(bankIndex: Int)(e: ReactEventFromInput): Callback = {
          val maybeValue = Try(e.target.value.toInt).toOption
          val updatedBankSizes = state.simulationFormFields.eGateBanksSizes.indices.zip(state.simulationFormFields.eGateBanksSizes).map {
            case (idx, existingBankSize) => if (idx == bankIndex) maybeValue else existingBankSize
          }
          scope.setState(state.copy(simulationFormFields = state.simulationFormFields.copy(eGateBanksSizes = updatedBankSizes)))
        }

        def changeProcessingTimes(ptq: PaxTypeAndQueue): ReactEventFromInput => Callback = (e: ReactEventFromInput) => {
          val maybeValue = Try(e.target.value.toInt).toOption
          val updatedProcTimes = state.copy(
            simulationFormFields = state.simulationFormFields
              .copy(processingTimes = state.simulationFormFields.processingTimes + (ptq -> maybeValue)))
          scope.setState(updatedProcTimes)
        }

        def changeQueueSla(q: Queues.Queue): ReactEventFromInput => Callback = (e: ReactEventFromInput) => {
          val maybeValue = Try(e.target.value.toInt).toOption
          val updatedSlas = state.copy(
            simulationFormFields = state.simulationFormFields
              .copy(slaByQueue = state.simulationFormFields.slaByQueue + (q -> maybeValue)))
          scope.setState(updatedSlas)
        }

        def changeMinDesks(q: Queues.Queue): ReactEventFromInput => Callback = {
          (e: ReactEventFromInput) =>
            val maybeValue = Try(e.target.value.toInt).toOption
            val updatedMinDesks = state.copy(
              simulationFormFields = state
                .simulationFormFields
                .copy(minDesks = state.simulationFormFields.minDesks + (q -> maybeValue))
            )
            scope.setState(updatedMinDesks)
        }

        def changeMaxDesks(q: Queues.Queue): ReactEventFromInput => Callback = {
          (e: ReactEventFromInput) =>
            val maybeValue = Try(e.target.value.toInt).toOption
            val updatedMaxDesks = state.copy(
              simulationFormFields = state
                .simulationFormFields
                .copy(maxDesks = state.simulationFormFields.maxDesks + (q -> maybeValue))
            )
            scope.setState(updatedMaxDesks)
        }

        def toggleEGateHour(hour: Int): CallbackTo[Unit] = scope.modState(_.toggleEGateHour(hour))

        def openEgatesAllDay = scope.modState(_.openEgatesAllDay)

        def closeEgatesAllDay = scope.modState(_.closeEgatesAllDay)

        def passengerWeightingFields = {

          <.div(
            DefaultFormFieldsStyle.formHelperText,
            MuiTextField(
              label = "Passenger weighting".toVdom,
              margin = MuiTextField.Margin.normal,
              helperText = MuiTypography(variant = MuiTypography.Variant.caption)(
                "e.g. '2' will give you double the passengers on each flight."
              )
            )(
              DefaultFormFieldsStyle.textField,
              `type` := "number",
              id := "passenger-weighting",
              value := state.simulationFormFields.passengerWeighting.map(_.toString).getOrElse(""),
              onChange ==> changePassengerWeighting
            ))
        }

        def processingTimesFields = {
          <.div(
            state.simulationFormFields.processingTimes.toList.sortBy {
              case (paxTypeAndQueue: PaxTypeAndQueue, _) => paxTypeAndQueue.passengerType.name
            }.map {
              case (ptq, maybeProcTime) =>
                val queueDisplayName = Queues.displayName(ptq.queueType)
                <.div(^.className := "form-check", ^.key := ptq.key,
                  DefaultFormFieldsStyle.formHelperText,
                  MuiTextField(
                    label = s"${PaxTypes.displayNameShort(ptq.passengerType)} to $queueDisplayName".toVdom,
                    margin = MuiTextField.Margin.normal,
                    helperText = MuiTypography(variant = MuiTypography.Variant.caption)(
                      s"Seconds to process ${PaxTypes.displayName(ptq.passengerType)} at $queueDisplayName"
                    )
                  )(
                    DefaultFormFieldsStyle.textField,
                    `type` := "number",
                    id := s"${ptq.passengerType}_${ptq.queueType}",
                    value := maybeProcTime.map(_.toString).getOrElse(""),
                    onChange ==> changeProcessingTimes(ptq)
                  )
                )
            }.toTagMod
          )
        }

        def slaFields = {
          <.div(
            state.simulationFormFields.slaByQueue.map {
              case (q, maybeSla) =>
                <.div(^.className := "form-check", ^.key := q.toString,
                  MuiTextField(
                    label = s"${Queues.displayName(q)} (at least 3 minutes)".toVdom,
                    margin = MuiTextField.Margin.normal
                  )(
                    DefaultFormFieldsStyle.textField,
                    `type` := "number",
                    id := s"${q}_sla",
                    value := maybeSla.map(_.toString).getOrElse(""),
                    onChange ==> changeQueueSla(q)
                  )
                )
            }.toTagMod
          )
        }

        def minMaxDesksFields: VdomTagOf[Div] = {
          <.div(
            state.simulationFormFields.minDesks.keys.map { q =>
              <.div(
                ^.className := "form-check", ^.key := q.toString,
                MuiFormLabel()(
                  DefaultFormFieldsStyle.labelWide,
                  s"${Queues.displayName(q)}"
                ),
                MuiTextField(
                  label = s"Min".toVdom,
                  margin = MuiTextField.Margin.normal
                )(
                  DefaultFormFieldsStyle.textFieldSmall,
                  `type` := "number",
                  id := s"${q}_min",
                  value := state.simulationFormFields.minDesks(q).map(_.toString).getOrElse(""),
                  onChange ==> changeMinDesks(q)
                ),
                MuiTextField(
                  label = s"Max".toVdom,
                  margin = MuiTextField.Margin.normal
                )(
                  DefaultFormFieldsStyle.textFieldSmall,
                  `type` := "number",
                  id := s"${q}_max",
                  value := state.simulationFormFields.maxDesks(q).map(_.toString).getOrElse(""),
                  onChange ==> changeMaxDesks(q)
                ),
              )
            }.toTagMod,
            <.div(
              state.simulationFormFields.eGateBanksSizes.zipWithIndex.map { case (bankSize, idx) =>
                <.div(^.key := s"bank-size-$idx",
                  MuiTextField(
                    label = s"e-Gates in bank ${idx + 1}".toVdom,
                    margin = MuiTextField.Margin.normal
                  )(
                    DefaultFormFieldsStyle.textField,
                    `type` := "number",
                    id := s"egate-bank-size-$idx",
                    value := bankSize.map(_.toString).getOrElse(""),
                    onChange ==> changeBankSize(idx)
                  )
                )
              }.toTagMod
            ),
          )
        }

        def eGatesOpen: VdomElement =
          MuiFormGroup()(
            MuiButton()("Select All", onClick --> openEgatesAllDay),
            MuiButton()("De-select All", onClick --> closeEgatesAllDay),
            (0 to 23)
              .map(hour =>
                <.div(^.key := s"egate-open-hour-$hour",
                  MuiInputLabel()(f"$hour%02d:00"), MuiCheckbox()(
                    ^.value := hour.toString,
                    ^.checked := state.simulationFormFields.eGateOpenHours.contains(hour),
                    ^.onClick --> toggleEGateHour(hour)
                  )
                )
              ).toVdomArray)

        def togglePanel(panel: String): CallbackTo[Unit] = scope.modState(_.toggle(panel))

        def isOpen(panel: String): Boolean = state.isOpen(panel)

        val showCharts: Callback = Callback(SPACircuit.dispatch(GetSimulation(state.simulationFormFields)))

        <.div(
          DefaultFormFieldsStyle.regularText,
          MuiExpansionPanel(square = true, expanded = isOpen("passengerWeighting"))(
            onChange --> togglePanel("passengerWeighting"),
            MuiExpansionPanelSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
              MuiTypography(variant = MuiTypography.Variant.inherit)(
                ^.id := "adjust_passenger_numbers",
                "Adjust Passenger Numbers"
              )
            ),
            MuiExpansionPanelDetails()(passengerWeightingFields)
          ),
          MuiExpansionPanel(square = true, expanded = isOpen("processingTimes"))(
            onChange --> togglePanel("processingTimes"),
            MuiExpansionPanelSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
              MuiTypography(variant = MuiTypography.Variant.inherit)(
                ^.id := "adjust_processing_times",
                "Adjust Processing Times"
              )
            ),
            MuiExpansionPanelDetails()(processingTimesFields)
          ),
          MuiExpansionPanel(square = true, expanded = isOpen("slaFields"))(
            onChange --> togglePanel("slaFields"),
            MuiExpansionPanelSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
              MuiTypography(variant = MuiTypography.Variant.inherit)(
                ^.id := "adjust_queue_slas",
                "Adjust Queue SLAs"
              )
            ),
            MuiExpansionPanelDetails()(slaFields)
          ),
          MuiExpansionPanel(square = true, expanded = isOpen("minMaxDesksFields"))(
            onChange --> togglePanel("minMaxDesksFields"),
            MuiExpansionPanelSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
              MuiTypography(variant = MuiTypography.Variant.inherit)(
                ^.id := "adjust_available_desks",
                "Adjust Available Desks"
              )
            ),
            MuiExpansionPanelDetails()(minMaxDesksFields)
          ),
          MuiExpansionPanel(square = true, expanded = isOpen("configureEGatesFields"))(
            onChange --> togglePanel("configureEGatesFields"),
            MuiExpansionPanelSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
              MuiTypography(variant = MuiTypography.Variant.inherit)(
                ^.id := "adjust_egate_open_times",
                "Adjust eGate Open Times"
              )
            ),
            MuiExpansionPanelDetails()(eGatesOpen)
          ),
          MuiDivider(variant = MuiDivider.Variant.middle)(),
          <.div(
            DefaultFormFieldsStyle.buttons,
            submitButton(showCharts, state.simulationFormFields),
            MuiButton(
              variant = MuiButton.Variant.contained,
              color = MuiButton.Color.default,
            )(
              ^.className := "button",
              ^.target := "_blank",
              ^.id := "export-simulation",
              ^.href := SPAMain.absoluteUrl(s"export/desk-rec-simulation?${state.simulationFormFields.toQueryStringParams}"),
              "Export"
            )
          )
        )
    }
    .configure(Reusability.shouldComponentUpdate)
    .componentDidMount(_ => Callback {
      GoogleEventTracker.sendPageView(s"Arrival Simulations Page")
    })
    .build

  private def submitButton(showCharts: Callback, form: SimulationFormFields): WithPropsAndTagsMods = {
    val (colour, callback) = if (form.isValid)
      (MuiButton.Color.primary, showCharts)
    else
      (MuiButton.Color.default, Callback(() => Unit))

    MuiButton(
      variant = MuiButton.Variant.contained,
      color = colour
    )(onClick --> callback, "Update")
  }

  def apply(date: LocalDate, terminal: Terminal, airportConfg: AirportConfig): VdomElement =
    component(Props(date, terminal, airportConfg))
}


