package drt.client.components.scenarios

import drt.client.SPAMain
import drt.client.actions.Actions.GetSimulation
import drt.client.components.Helpers._
import drt.client.components.styles.DefaultFormFieldsStyle
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.dates.LocalDate
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.icons.{MuiIcons, MuiIconsModule}
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.all.{`type`, id, onChange, onClick, value}
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import scalacss.ScalaCssReactImplicits

import scala.util.{Success, Try}

object ScenarioSimulationFormComponent extends ScalaCssReactImplicits {

  implicit val stateReuse: Reusability[State] = Reusability.by_==[State]
  implicit val propsReuse: Reusability[Props] = Reusability.by_==[Props]

  val steps = List("Passenger numbers", "Processing Times", "Queue SLAs", "Configure Desk Availability")


  case class State(simulationParams: SimulationParams, panelStatus: Map[String, Boolean]) {

    def isOpen(panel: String) = panelStatus.getOrElse(panel, false)

    def toggle(panel: String) = copy(
      panelStatus = panelStatus + (panel -> !isOpen(panel))
    )
  }

  case class Props(
                    date: LocalDate,
                    terminal: Terminal,
                    airportConfig: AirportConfig,
                  )

  val component = ScalaComponent.builder[Props]("SimulationConfiguration")
    .initialStateFromProps(p =>
      State(SimulationParams(p.terminal, p.date, p.airportConfig), Map())
    )
    .renderS {

      (scope, state) =>

        def changePassengerWeighting(e: ReactEventFromInput): Callback = Try(e.target.value.toDouble) match {
          case Success(weight) =>
            scope.setState(state.copy(simulationParams = state.simulationParams.copy(passengerWeighting = weight)))
          case _ =>
            Callback.empty
        }

        def changeBankSize(e: ReactEventFromInput): Callback = Try(e.target.value.toInt) match {
          case Success(bs) =>
            scope.setState(state.copy(simulationParams = state.simulationParams.copy(eGateBanksSize = bs)))
          case _ =>
            Callback.empty
        }

        def changeProcessingTimes(ptq: PaxTypeAndQueue): ReactEventFromInput => Callback = (e: ReactEventFromInput) =>
          Try(e.target.value.toInt) match {
            case Success(procTimes) =>
              scope.setState(state.copy(
                simulationParams = state
                  .simulationParams
                  .copy(processingTimes = state.simulationParams.processingTimes + (ptq -> procTimes))
              ))
            case _ => Callback.empty
          }

        def changeQueueSla(q: Queues.Queue): ReactEventFromInput => Callback = (e: ReactEventFromInput) =>
          Try(e.target.value.toInt) match {
            case Success(sla) if sla >= 3 =>
              scope.setState(state.copy(
                simulationParams = state
                  .simulationParams
                  .copy(slaByQueue = state.simulationParams.slaByQueue + (q -> sla))
              ))
            case _ => Callback.empty
          }

        def changeMinDesks(q: Queues.Queue): ReactEventFromInput => Callback = {
          (e: ReactEventFromInput) =>
            Try(e.target.value.toInt) match {
              case Success(min) =>
                scope.setState(state.copy(
                  simulationParams = state
                    .simulationParams
                    .copy(minDesks = state.simulationParams.minDesks + (q -> min))
                ))
              case _ => Callback.empty
            }
        }

        def changeMaxDesks(q: Queues.Queue): ReactEventFromInput => Callback = {
          (e: ReactEventFromInput) =>
            Try(e.target.value.toInt) match {
              case Success(max) =>
                scope.setState(state.copy(
                  simulationParams = state
                    .simulationParams
                    .copy(maxDesks = state.simulationParams.maxDesks + (q -> max))
                ))
              case _ => Callback.empty
            }
        }

        def passengerWeightingFields = {

          <.div(
            MuiTextField(
              label = "Passenger weighting".toVdom,
              margin = MuiTextField.Margin.normal,
              helperText = "This is a multiplier for the number of passengers on each flight, e.g. '2' will give you double the passengers on each flight.".toVdom
            )(
              DefaultFormFieldsStyle.textField,
              `type` := "number",
              id := "passenger-weighting",
              value := state.simulationParams.passengerWeighting,
              onChange ==> changePassengerWeighting
            ))
        }

        def processingTimesFields = {
          <.div(^.className := "",
            state.simulationParams.processingTimes.map {
              case (ptq, _) =>
                <.div(^.className := "form-check",
                  MuiTextField(
                    label = s"${PaxTypes.displayName(ptq.passengerType)} to ${Queues.queueDisplayNames(ptq.queueType)}".toVdom,
                    margin = MuiTextField.Margin.normal,
                    helperText = s"Average seconds to process ${PaxTypes.displayName(ptq.passengerType)} at ${Queues.queueDisplayNames(ptq.queueType)}".toVdom
                  )(
                    DefaultFormFieldsStyle.textField,
                    `type` := "number",
                    id := "egate-bank-size",
                    value := state.simulationParams.processingTimes(ptq),
                    onChange ==> changeProcessingTimes(ptq)
                  )
                )
            }.toTagMod
          )
        }

        def slaFields = {
          <.div(
            state.simulationParams.slaByQueue.map {
              case (q, _) =>
                <.div(^.className := "form-check",
                  MuiTextField(
                    label = s"${Queues.queueDisplayNames(q)} (at least 3 minutes)".toVdom,
                    margin = MuiTextField.Margin.normal
                  )(
                    DefaultFormFieldsStyle.textField,
                    `type` := "number",
                    id := "egate-bank-size",
                    value := state.simulationParams.slaByQueue(q),
                    onChange ==> changeQueueSla(q)
                  )
                )
            }.toTagMod
          )
        }

        def minMaxDesksFields = {
          <.div(
            state.simulationParams.minDesks.keys.map {
              case q =>
                <.div(
                  ^.className := "form-check",
                  MuiFormLabel()(
                    DefaultFormFieldsStyle.labelWide,
                    s"${Queues.queueDisplayNames(q)} ${Queues.queueDisplayNames(q)}"
                  ),
                  MuiTextField(
                    label = s"Min".toVdom,
                    margin = MuiTextField.Margin.normal
                  )(
                    DefaultFormFieldsStyle.textFieldSmall,
                    `type` := "number",
                    id := s"${q}_min",
                    value := state.simulationParams.minDesks(q),
                    onChange ==> changeMinDesks(q)
                  ),
                  MuiTextField(
                    label = s"Max".toVdom,
                    margin = MuiTextField.Margin.normal
                  )(
                    DefaultFormFieldsStyle.textFieldSmall,
                    `type` := "number",
                    id := s"${q}_max",
                    value := state.simulationParams.maxDesks(q),
                    onChange ==> changeMaxDesks(q)
                  ),
                )
            }.toTagMod,
            <.div(
              MuiTextField(
                label = "E-Gate bank size".toVdom,
                margin = MuiTextField.Margin.normal
              )(
                DefaultFormFieldsStyle.textField,
                `type` := "number",
                id := "egate-bank-size",
                value := state.simulationParams.eGateBanksSize,
                onChange ==> changeBankSize
              )
            ),
          )
        }

        def togglePanel(panel: String): CallbackTo[Unit] = scope.modState(_.toggle(panel))

        def isOpen(panel: String): Boolean = state.isOpen(panel)

        def showCharts = Callback(SPACircuit.dispatch(GetSimulation(state.simulationParams)))

        <.div(
          MuiExpansionPanel(square = true, expanded = isOpen("passengerWeighting"))(
            onChange --> togglePanel("passengerWeighting"),
            MuiExpansionPanelSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
              MuiTypography()(
                "Adjust Passenger Numbers"
              )
            ),
            MuiExpansionPanelDetails()(passengerWeightingFields)
          ),
          MuiExpansionPanel(square = true, expanded = isOpen("processingTimes"))(
            onChange --> togglePanel("processingTimes"),
            MuiExpansionPanelSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
              MuiTypography()(
                "Adjust Processing Times"
              )
            ),
            MuiExpansionPanelDetails()(processingTimesFields)
          ),
          MuiExpansionPanel(square = true, expanded = isOpen("slaFields"))(
            onChange --> togglePanel("slaFields"),
            MuiExpansionPanelSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
              MuiTypography()(
                "Adjust Queue SLAs"
              )
            ),
            MuiExpansionPanelDetails()(slaFields)
          ),
          MuiExpansionPanel(square = true, expanded = isOpen("minMaxDesksFields"))(
            onChange --> togglePanel("minMaxDesksFields"),
            MuiExpansionPanelSummary(expandIcon = MuiIcons(MuiIconsModule.ExpandMore)()())(
              MuiTypography()(
                "Adjust Available Desks"
              )
            ),
            MuiExpansionPanelDetails()(minMaxDesksFields)
          ),
          MuiDivider(variant = MuiDivider.Variant.middle)(),
          <.div(
            DefaultFormFieldsStyle.buttons,
            MuiButton(
              variant = MuiButton.Variant.contained,
              color = MuiButton.Color.primary,
            )(
              onClick --> showCharts,
              "Update"
            ),
            MuiButton(
              variant = MuiButton.Variant.contained,
              color = MuiButton.Color.default,
            )(
              ^.className := "button",
              ^.target := "_blank",
              ^.href := SPAMain.absoluteUrl(s"export/desk-rec-simulation?${state.simulationParams.toQueryStringParams}"),
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

  def apply(date: LocalDate, terminal: Terminal, airportConfg: AirportConfig): VdomElement =
    component(Props(date, terminal, airportConfg))
}


