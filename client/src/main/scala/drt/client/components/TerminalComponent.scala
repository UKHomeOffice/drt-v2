package drt.client.components

import diode.data.{Pending, Pot}
import diode.react.ModelProxy
import drt.client.components.FlightComponents.SplitsGraph.splitsGraphComponentColoured
import drt.client.components.FlightComponents.paxComp
import drt.client.components.Heatmap.Series
import drt.client.components.TerminalHeatmaps._
import drt.client.logger.log
import drt.client.services.HandyStuff.QueueStaffDeployments
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{SPACircuit, Workloads}
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared.Simulations.QueueSimulationResult
import drt.shared._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{BackendScope, Callback, ScalaComponent}

import scala.collection.immutable.Map
import scala.util.Try

object TerminalComponent {

  case class Props(terminalName: TerminalName)

  case class TerminalModel(
                            airportConfig: Pot[AirportConfig],
                            airportInfos: Pot[AirportInfo],
                            simulationResult: Map[QueueName, QueueSimulationResult],
                            crunchResult: Map[QueueName, CrunchResult],
                            deployments: QueueStaffDeployments,
                            workloads: Workloads,
                            actualDesks: Map[QueueName, Map[Long, DeskStat]],
                            flightsWithSplitsPot: Pot[FlightsWithSplits])

  def render(props: Props) = {
    val modelRCP = SPACircuit.connect(model => TerminalModel(
      model.airportConfig,
      model.airportInfos.getOrElse(props.terminalName, Pending()),
      model.simulationResult.getOrElse(props.terminalName, Map()),
      model.queueCrunchResults.getOrElse(props.terminalName, Map()),
      model.staffDeploymentsByTerminalAndQueue.getOrElse(props.terminalName, Map()),
      model.workloadPot.getOrElse(Workloads(Map())),
      model.actualDeskStats.getOrElse(props.terminalName, Map()),
      model.flightsWithSplitsPot
    ))

    modelRCP(modelMP => {
      val model = modelMP.value
      <.div(
        model.airportConfig.renderReady(airportConfig => {
          val summaryBoxesProps = SummaryBoxesComponent.Props(
            airportConfig,
            props.terminalName,
            model.flightsWithSplitsPot
          )
          val heatmapProps = HeatmapComponent.Props(
            props.terminalName,
            model.simulationResult)

          val terminalContentProps = TerminalContentComponent.Props(
            airportConfig,
            props.terminalName,
            model.airportInfos,
            model.flightsWithSplitsPot,
            model.simulationResult,
            model.crunchResult,
            model.deployments,
            model.workloads,
            model.actualDesks
          )

          <.div(
            SummaryBoxesComponent(summaryBoxesProps),
            HeatmapComponent(heatmapProps),
            TerminalContentComponent(terminalContentProps)
          )
        }
        )
      )
    })
  }

  implicit val propsReuse = Reusability.caseClass[Props]

  val component = ScalaComponent.builder[Props]("Terminal")
    .renderPS(($, props, state) => render(props))
    .build

  def apply(props: Props): VdomElement = component(props)
}

object SummaryBoxesComponent {

  case class Props(
                    airportConfig: AirportConfig,
                    terminalName: TerminalName,
                    flightsWithSplitsPot: Pot[FlightsWithSplits]
                  ) {
    lazy val hash = Nil
  }

  val component = ScalaComponent.builder[Props]("SummaryBoxes")
    .render_P(props => {
      val portCode = props.airportConfig.portCode
      val queueOrder = props.airportConfig.queueOrder
      val bestPaxFn = BestPax(portCode)
      val now = SDate.now()
      val hoursToAdd = 3
      val nowplus3 = now.addHours(hoursToAdd)

      <.div(
        <.h2(s"In the next $hoursToAdd hours"),
        <.div({
          props.flightsWithSplitsPot.renderReady(flightsWithSplits => {
            import BigSummaryBoxes._
            val tried: Try[VdomElement] = Try {
              val filteredFlights = flightsInPeriod(flightsWithSplits.flights, now, nowplus3)
              val flightsAtTerminal = BigSummaryBoxes.flightsAtTerminal(filteredFlights, props.terminalName)
              val flightCount = flightsAtTerminal.length
              val actPax = sumActPax(flightsAtTerminal)
              val bestSplitPaxFn = bestFlightSplitPax(bestPaxFn)
              val bestPax = sumBestPax(bestSplitPaxFn)(flightsAtTerminal).toInt
              val aggSplits = aggregateSplits(bestPaxFn)(flightsAtTerminal)

              val summaryBoxes = SummaryBox(BigSummaryBoxes.Props(flightCount, actPax, bestPax, aggSplits, paxQueueOrder = queueOrder))

              <.div(summaryBoxes)
            }
            val recovered = tried recoverWith {
              case f => Try(<.div(f.toString))
            }
            <.span(recovered.get)
          })
        },
          props.flightsWithSplitsPot.renderPending(_ => "Waiting for flights")
        )
      )
    })
    .build

  def apply(props: Props): VdomElement = component(props)
}

object HeatmapComponent {

  case class Props(
                    terminalName: TerminalName,
                    simulationResults: Map[QueueName, QueueSimulationResult]
                  ) {
    lazy val hash = simulationResults.values.map(_.hashCode).hashCode()
  }

  case class State(activeTab: String)

  implicit val propsReuse = Reusability.by((_: Props).hash)
  implicit val stateReuse = Reusability.caseClass[State]

  val component = ScalaComponent.builder[Props]("Heatmaps")
    .initialState(State("deskrecs"))
    .renderPS((scope, props, state) =>
      <.div({
        val seriesPot: Pot[List[Series]] = waitTimes(props.simulationResults, props.terminalName)
        <.div(
          <.ul(^.className := "nav nav-tabs",
            <.li(^.className := "active", <.a(VdomAttr("data-toggle") := "tab", ^.href := "#deskrecs", "Desk recommendations"), ^.onClick --> scope.modState(_ => State("deskrecs"))),
            <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#workloads", "Workloads"), ^.onClick --> scope.modState(_ => State("workloads"))),
            <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#paxloads", "Paxloads"), ^.onClick --> scope.modState(_ => State("paxloads"))),
            <.li(seriesPot.renderReady(s => {
              <.a(VdomAttr("data-toggle") := "tab", ^.href := "#waits", "Wait times", ^.onClick --> scope.modState(_ => State("waits")))
            }))
          )
          ,
          <.div(^.className := "tab-content",
            <.div(^.id := "deskrecs", ^.className := "tab-pane fade in active",
              if (state.activeTab == "deskrecs") {
                heatmapOfStaffDeploymentDeskRecs(props.terminalName)
              } else ""),
            <.div(^.id := "workloads", ^.className := "tab-pane fade",
              if (state.activeTab == "workloads") {
                heatmapOfWorkloads(props.terminalName)
              } else ""),
            <.div(^.id := "paxloads", ^.className := "tab-pane fade",
              if (state.activeTab == "paxloads") {
                heatmapOfPaxloads(props.terminalName)
              } else ""),
            <.div(^.id := "waits", ^.className := "tab-pane fade",
              if (state.activeTab == "waits") {
                heatmapOfWaittimes(props.terminalName, props.simulationResults)
              } else "")
          ))
      }))
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}

object TerminalContentComponent {

  case class Props(
                    airportConfig: AirportConfig,
                    terminalName: TerminalName,
                    airportInfoPot: Pot[AirportInfo],
                    flightsWithSplitsPot: Pot[FlightsWithSplits],
                    simulationResult: Map[QueueName, QueueSimulationResult],
                    crunchResult: Map[QueueName, CrunchResult],
                    deployments: QueueStaffDeployments,
                    workloads: Workloads,
                    actualDesks: Map[QueueName, Map[Long, DeskStat]]
                  ) {
    lazy val hash = {
      val depsHash: List[Option[List[Int]]] = deployments.values.map(drtsPot => {
        drtsPot.toOption.map(drts => {
          drts.items.map(drt => {
            drt.hashCode
          }).toList
        })
      }).toList

      val flightsHash: Option[List[(Int, String, String, String, String, String, String, String, String, Long, Int)]] = flightsWithSplitsPot.toOption.map(_.flights.map(f => {
        (f.splits.hashCode,
          f.apiFlight.Status,
          f.apiFlight.Gate,
          f.apiFlight.Stand,
          f.apiFlight.SchDT,
          f.apiFlight.EstDT,
          f.apiFlight.ActDT,
          f.apiFlight.EstChoxDT,
          f.apiFlight.ActChoxDT,
          f.apiFlight.PcpTime,
          f.apiFlight.ActPax
        )
      }))

      (depsHash, flightsHash)
    }
  }

  val timelineComp: Option[(Arrival) => html_<^.VdomElement] = Some(FlightTableComponents.timelineCompFunc _)

  def airportWrapper(portCode: String) = SPACircuit.connect(_.airportInfos.getOrElse(portCode, Pending()))

  def originMapper(portCode: String): VdomElement = {
    Try {
      vdomElementFromComponent(airportWrapper(portCode) { (proxy: ModelProxy[Pot[AirportInfo]]) =>
        <.span(
          proxy().render(ai => <.span(^.title := s"${ai.airportName}, ${ai.city}, ${ai.country}", portCode)),
          proxy().renderEmpty(<.span(portCode)),
          proxy().renderPending((n) => <.span(portCode)))
      })
    }.recover {
      case e =>
        log.error(s"origin mapper error $e")
        vdomElementFromTag(<.div(portCode))
    }.get
  }

  case class State(activeTab: String)

  class Backend(t: BackendScope[Props, State]) {
    val arrivalsTableComponent = FlightsWithSplitsTable.ArrivalsTable(
      timelineComp,
      originMapper,
      splitsGraphComponentColoured)(paxComp(843))

    def render(props: Props, state: State) = {
      val bestPax = BestPax(props.airportConfig.portCode)
      val queueOrder = props.airportConfig.queueOrder

      <.div(
        <.ul(^.className := "nav nav-tabs",
          <.li(^.className := "active", <.a(VdomAttr("data-toggle") := "tab", ^.href := "#arrivals", "Arrivals"), ^.onClick --> t.modState(_ => State("arrivals"))),
          <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#queues", "Desks & Queues"), ^.onClick --> t.modState(_ => State("queues"))),
          <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#staffing", "Staffing"), ^.onClick --> t.modState(_ => State("staffing")))
        ),
        <.div(^.className := "tab-content",
          <.div(^.id := "arrivals", ^.className := "tab-pane fade in active", {
            if (state.activeTab == "arrivals") {
              val flights: Pot[FlightsApi.FlightsWithSplits] = props.flightsWithSplitsPot

              <.div(flights.renderReady((flightsWithSplits: FlightsWithSplits) => {
                val flightsForTerminal = FlightsWithSplits(flightsWithSplits.flights.filter(f => f.apiFlight.Terminal == props.terminalName))
                arrivalsTableComponent(FlightsWithSplitsTable.Props(flightsForTerminal, bestPax, queueOrder))
              }))
            } else ""
          }),
          <.div(^.id := "queues", ^.className := "tab-pane fade terminal-desk-recs-container",
            if (state.activeTab == "queues") {
              val deploymentProps = TerminalDeploymentsTable.TerminalProps(
                props.airportConfig,
                props.terminalName,
                props.flightsWithSplitsPot,
                props.simulationResult,
                props.crunchResult,
                props.deployments,
                props.workloads,
                props.actualDesks
              )
              TerminalDeploymentsTable.terminalDeploymentsComponent(deploymentProps)
            } else ""
          ),
          <.div(^.id := "staffing", ^.className := "tab-pane fade terminal-staffing-container",
            if (state.activeTab == "staffing") {
              TerminalStaffing(TerminalStaffing.Props(props.terminalName))
            } else ""
          )))

    }
  }

  implicit val propsReuse = Reusability.by((_: Props).hash)
  implicit val stateReuse = Reusability.caseClass[State]

  val component = ScalaComponent.builder[Props]("TerminalContentComponent")
    .initialState(State("arrivals"))
    .renderBackend[TerminalContentComponent.Backend]
    .componentDidMount((p) => Callback.log(s"terminal component didMount"))
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = {
    component(props)
  }
}
