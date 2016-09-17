package spatutorial.client.modules

import diode.react.ReactPot._
import diode.data.Pot
import diode.react._
import diode.util._
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.SPAMain.{Loc, TodoLoc}
import spatutorial.client.components.Bootstrap.Panel
import spatutorial.client.components._
import spatutorial.client.services.{UserDeskRecs, Crunch, GetWorkloads, Workloads}
import spatutorial.shared.FlightsApi.Flights
import spatutorial.shared.{DeskRec, CrunchResult, SimulationResult}

import scala.scalajs.js
import scala.util.Random
import scala.language.existentials
import spatutorial.client.logger._

object Dashboard {

  case class DashboardModels(workloads: Pot[Workloads],
                             potCrunchResult: Pot[CrunchResult],
                             potSimulationResult: Pot[SimulationResult],
                             potUserDeskRecs: Pot[UserDeskRecs]
                            )

  case class Props(router: RouterCtl[Loc], // proxy: ModelProxy[Pot[String]],
                   dashboardModelProxy: ModelProxy[DashboardModels]
                  )

  case class State(workloadsWrapper: ReactConnectProxy[Pot[Workloads]],
                   crunchResultWrapper: ReactConnectProxy[Pot[CrunchResult]],
                   simulationResultWrapper: ReactConnectProxy[Pot[SimulationResult]],
                   userDeskRecsWrapper: ReactConnectProxy[Pot[UserDeskRecs]]
                  )

  val baseDate = new js.Date(2016, 10, 1, 7)
  val millisPer15Minutes = 1000 * 60
  val numberOf15Mins = (24 * 4 * 15)
  // create dummy data for the chart
  val labelsDates = (baseDate.getTime() to (baseDate.getTime() + millisPer15Minutes * numberOf15Mins) by millisPer15Minutes).map(new js.Date(_))
  val labels = labelsDates.map(_.toISOString())

  //  private val workload: Seq[Double] = Iterator.continually(Random.nextDouble() * 250).take(numberOf15Mins).toSeq
  def cp(workload: Seq[Double]) = {
    log.debug(s"Workload is ${workload}")
    val safeWl = if (workload == null) Nil else workload
    Chart.ChartProps(
      "Test chart",
      Chart.LineChart,
      ChartData(
        labels,
        Seq(ChartDataset(safeWl, "Workload"))
      )
    )
  }

  // create dummy data for the chart
  val cpb = Chart.ChartProps(
    "Test chart",
    Chart.BarChart,
    ChartData(
      Random.alphanumeric.map(_.toUpper.toString).distinct.take(10),
      Seq(ChartDataset(Iterator.continually(Random.nextDouble() * 10).take(10).toSeq, "Data1"))
    ),
    width = 800
  )

  def mounted(props: Props) = {
    log.info("backend mounted")
    val cb: Callback = Callback.when(props.dashboardModelProxy().workloads.isEmpty) {
      props.dashboardModelProxy.dispatch(GetWorkloads("", "", "edi"))
      //      props.dashboardModelProxy.dispatch(Crunch(props.dashboardModelProxy.value.workloadsWrapper.get.workloadsWrapper))
    }
    cb
  }

  // create the React component for Dashboard
  private val component = ReactComponentB[Props]("Dashboard")
    // create and store the connect proxy in state for later use
    .initialState_P(props => State(
    props.dashboardModelProxy.connect(m => m.workloads),
    props.dashboardModelProxy.connect(m => m.potCrunchResult),
    props.dashboardModelProxy.connect(m => m.potSimulationResult),
    props.dashboardModelProxy.connect(_.potUserDeskRecs)
  ))
    .renderPS { (_, props, state: State) =>
      log.info(s"evaluating dashboard ${props}:${state}")
      <.div(
        // header, MessageOfTheDay and chart components
        <.h2("Dashboard"),
        //                state.motdWrapper(Motd(_)),
        state.simulationResultWrapper(srw =>
          state.workloadsWrapper(workloadsModelProxy => {
            val workloads: Pot[Workloads] = workloadsModelProxy.value
            <.div(
              workloads.renderReady(wl => Chart(cp(wl.workloads))),
              workloads.renderPending((num) => <.div(s"waiting with ${num}")),
              workloads.renderEmpty(<.div(s"Waiting for workload")))
          })),
        state.crunchResultWrapper(s =>
          DeskRecsChart(labels, props.dashboardModelProxy)),
        state.userDeskRecsWrapper(s => DeskRecsChart.DeskSimInputs(labels)(s)))
      /*
        state.simulationResultWrapper(simRes =>
          state.crunchResultWrapper((s: ModelProxy[Pot[CrunchResult]]) => DeskRecsChart(labels, props.dashboardModelProxy))),
          // create a link to the To Do view
          <.div(props.router.link(TodoLoc)("Check your todos!"))
        )
        */
    }
    .componentDidMount(scope => mounted(scope.props))
    .build

  def apply(router: RouterCtl[Loc], dashboardProps: ModelProxy[DashboardModels]) = component(Props(router, dashboardProps))
}
