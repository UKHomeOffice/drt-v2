package drt.client.components.scenarios

import diode.UseValueEq
import drt.client.SPAMain
import drt.client.actions.Actions.GetSimulation
import io.kinoplan.scalajs.react.material.ui.core._
import drt.client.components.styles.ScalaCssImplicits._
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import io.kinoplan.scalajs.react.bridge.WithPropsAndTagsMods
import io.kinoplan.scalajs.react.material.ui.icons.{MuiIcons, MuiIconsModule}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.all.{`type`, id, onChange, onClick, value}
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom.html.Div
import scalacss.ScalaCssReactImplicits
import uk.gov.homeoffice.drt.ports.Queues.EGate
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, PaxTypeAndQueue, PaxTypes, Queues}
import uk.gov.homeoffice.drt.time.LocalDate

import scala.scalajs.js
import scala.util.Try


object ScenarioSimulationFormComponent extends ScalaCssReactImplicits {

  val steps: Seq[String] = List("Passenger numbers", "Processing Times", "Queue SLAs", "Configure Desk Availability")

  case class State(simulationFormFields: SimulationFormFields, panelStatus: Map[String, Boolean]) {
    def isOpen(panel: String): Boolean = panelStatus.getOrElse(panel, false)

    def toggle(panel: String): State = copy(
      panelStatus = panelStatus + (panel -> !isOpen(panel))
    )

    def toggleEGateHour(hour: Int): State = copy(simulationFormFields = simulationFormFields.toggleEgateHour(hour))

    def openEgatesAllDay: State = copy(simulationFormFields = simulationFormFields.openEgatesAllDay)

    def closeEgatesAllDay: State = copy(simulationFormFields = simulationFormFields.closeEgatesAllDay)
  }

  case class Props(date: LocalDate, terminal: Terminal, airportConfig: AirportConfig, slaConfigs: SlaConfigs) extends UseValueEq

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("SimulationFormComponent")
    .initialStateFromProps(p =>
      State(SimulationFormFields(p.terminal, p.date, p.airportConfig, p.slaConfigs), Map())
    )
    .renderPS {

      (scope, props, state) =>

        def changePassengerWeighting(e: ReactEventFromInput): Callback = {
          val maybeValue = Try(e.target.value.toDouble).toOption
          scope.setState(state.copy(simulationFormFields = state.simulationFormFields.copy(passengerWeighting = maybeValue)))
        }

        def changeBankSize(bankIndex: Int)(e: ReactEventFromInput): Callback = {
          val maybeValue = Try(e.target.value.toInt).toOption
          val updatedBankSizes = state.simulationFormFields.eGateBankSizes.indices.zip(state.simulationFormFields.eGateBankSizes).map {
            case (idx, existingBankSize) => if (idx == bankIndex) maybeValue else existingBankSize
          }
          scope.setState(state.copy(simulationFormFields = state.simulationFormFields.copy(eGateBankSizes = updatedBankSizes)))
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
                .copy(minDesksByQueue = state.simulationFormFields.minDesksByQueue + (q -> maybeValue))
            )
            scope.setState(updatedMinDesks)
        }

        def changeTerminalDesks: ReactEventFromInput => Callback = {
          (e: ReactEventFromInput) =>
            val maybeValue = Try(e.target.value.toInt).toOption
            val updatedMaxDesks = state.copy(
              simulationFormFields = state
                .simulationFormFields
                .copy(terminalDesks = maybeValue.getOrElse(state.simulationFormFields.terminalDesks))
            )
            scope.setState(updatedMaxDesks)
        }

        def toggleEGateHour(hour: Int): CallbackTo[Unit] = scope.modState(_.toggleEGateHour(hour))

        def openEgatesAllDay = scope.modState(_.openEgatesAllDay)

        def closeEgatesAllDay = scope.modState(_.closeEgatesAllDay)

        def passengerWeightingFields = {

          <.div(
            MuiTextField(
              label = "Passenger weighting".toVdom,
              helperText = MuiTypography(variant = MuiTypography.Variant.caption)(
                "e.g. '2' will give you double the passengers on each flight."
              )
            )(
              `type` := "number",
              id := "passenger-weighting",
              value := state.simulationFormFields.passengerWeighting.map(_.toString).getOrElse(""),
              onChange ==> changePassengerWeighting
            ))
        }

        def processingTimesFields = {
          <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "column", "gap" -> "16px"),
            state.simulationFormFields.processingTimes.toList.sortBy {
              case (paxTypeAndQueue: PaxTypeAndQueue, _) => paxTypeAndQueue.passengerType.name
            }.map {
              case (ptq, maybeProcTime) =>
                val queueDisplayName = Queues.displayName(ptq.queueType)
                MuiTextField(
                  label = s"Seconds to process ${PaxTypes.displayNameShort(ptq.passengerType, isBeforeAgeEligibilityChangeDate = false)} to $queueDisplayName".toVdom,
                )(
                  ^.key := ptq.key,
                  `type` := "number",
                  id := s"${ptq.passengerType}_${ptq.queueType}",
                  value := maybeProcTime.map(_.toString).getOrElse(""),
                  onChange ==> changeProcessingTimes(ptq)
                )
            }.toTagMod
          )
        }

        def slaFields = {
          <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "column", "gap" -> "16px"),
            state.simulationFormFields.slaByQueue.map {
              case (q, maybeSla) =>
                <.div(^.className := "form-check", ^.key := q.toString,
                  MuiTextField(
                    label = s"${Queues.displayName(q)} (at least 3 minutes)".toVdom,
                  )(
                    `type` := "number",
                    id := s"${q}_sla",
                    value := maybeSla.map(_.toString).getOrElse(""),
                    onChange ==> changeQueueSla(q)
                  )
                )
            }.toTagMod
          )
        }

        def desksAndBanksFields: VdomTagOf[Div] = {
          <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "column", "gap" -> "16px"),
            MuiTextField(
              label = s"Total desks".toVdom,
            )(
              `type` := "number",
              id := s"terminal-desks",
              value := state.simulationFormFields.terminalDesks.toString,
              onChange ==> changeTerminalDesks,
            ),
            state.simulationFormFields.minDesksByQueue.keys.map { q =>
              <.div(^.key := q.toString,
                <.div(
                  MuiTextField(
                    label = s"Minimum ${Queues.displayName(q)} ${if (q == EGate) "banks" else "desks"}".toVdom
                  )(
                    `type` := "number",
                    id := s"${q}_min",
                    value := state.simulationFormFields.minDesksByQueue(q).map(_.toString).getOrElse(""),
                    onChange ==> changeMinDesks(q)
                  ),
                )
              )
            }.toTagMod,
            if (state.simulationFormFields.eGateBankSizes.nonEmpty)
              <.div(
                MuiFormLabel()(
                  s"${Queues.displayName(EGate)} bank sizes"
                ),
                <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "column", "gap" -> "16px"),
                  state.simulationFormFields.eGateBankSizes.zipWithIndex.map { case (bankSize, idx) =>
                    MuiTextField(
                      label = s"Bank ${idx + 1} gates".toVdom,
                    )(
                      ^.key := s"bank-size-$idx",
                      `type` := "number",
                      id := s"egate-bank-size-$idx",
                      value := bankSize.map(_.toString).getOrElse(""),
                      onChange ==> changeBankSize(idx)
                    )
                  }.toTagMod
                )
              ) else <.div(),
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

        val terminalHasEgates = props.airportConfig.queuesByTerminal(props.terminal).contains(EGate)

        <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "column", "gap" -> "16px"),
          MuiAccordion(expanded = isOpen("passengerWeighting"))(
            onChange --> togglePanel("passengerWeighting"),
            MuiAccordionSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
              MuiTypography(variant = MuiTypography.Variant.inherit)(
                ^.id := "adjust_passenger_numbers",
                "Adjust Passenger Numbers"
              )
            ),
            MuiAccordionDetails()(passengerWeightingFields)
          ),
          MuiAccordion(expanded = isOpen("processingTimes"))(
            onChange --> togglePanel("processingTimes"),
            MuiAccordionSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
              MuiTypography(variant = MuiTypography.Variant.inherit)(
                ^.id := "adjust_processing_times",
                "Adjust Processing Times"
              )
            ),
            MuiAccordionDetails()(processingTimesFields)
          ),
          MuiAccordion(expanded = isOpen("slaFields"))(
            onChange --> togglePanel("slaFields"),
            MuiAccordionSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
              MuiTypography(variant = MuiTypography.Variant.inherit)(
                ^.id := "adjust_queue_slas",
                "Adjust Queue SLAs"
              )
            ),
            MuiAccordionDetails()(slaFields)
          ),
          MuiAccordion(expanded = isOpen("minMaxDesksFields"))(
            onChange --> togglePanel("minMaxDesksFields"),
            MuiAccordionSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
              MuiTypography(variant = MuiTypography.Variant.inherit)(
                ^.id := "adjust_available_desks",
                if (terminalHasEgates) "Adjust Desks & Egate banks" else "Adjust Desks"
              )
            ),
            MuiAccordionDetails()(desksAndBanksFields)
          ),
          if (terminalHasEgates)
            MuiAccordion(expanded = isOpen("configureEGatesFields"))(
              onChange --> togglePanel("configureEGatesFields"),
              MuiAccordionSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
                MuiTypography(variant = MuiTypography.Variant.inherit)(
                  ^.id := "adjust_egate_open_times",
                  "Adjust eGate Open Times"
                )
              ),
              MuiAccordionDetails()(eGatesOpen)
            )
          else <.div(),
          MuiDivider(variant = MuiDivider.Variant.middle)(),
          <.div(^.style := js.Dictionary("display" -> "flex", "gap" -> "8px"),
            submitButton(showCharts, state.simulationFormFields),
            MuiButton(
              variant = MuiButton.Variant.contained,
              color = MuiButton.Color.primary,
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
    .build

  private def submitButton(showCharts: Callback, form: SimulationFormFields): WithPropsAndTagsMods = {
    val (colour, callback) = if (form.isValid)
      (MuiButton.Color.primary, showCharts)
    else
      (MuiButton.Color.secondary, Callback.empty)

    MuiButton(
      variant = MuiButton.Variant.contained,
      color = colour
    )(onClick --> callback, "Update")
  }

  def apply(date: LocalDate, terminal: Terminal, airportConfg: AirportConfig, slaConfigs: SlaConfigs): VdomElement =
    component(Props(date, terminal, airportConfg, slaConfigs))
}


