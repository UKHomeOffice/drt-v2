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
import spatutorial.shared._

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
  val labels = labelsDates.map(_.toISOString().take(16))

  def chartDataFromWorkloads(workloads: Iterable[QueueWorkloads]): Map[String, List[Double]] = {
    val timesMin = workloads.flatMap(_.workloadsByMinute.map(_.time)).min
    val allMins = (timesMin until (timesMin + 60 * 60 * 24) by 60)
    println(allMins.length)
    val queueWorkloadsByMinute = workloads
      .map(queue => {
        val workloadsByMinute = queue.workloadsByMinute.map((wl) => (wl.time, wl.workload)).toMap
        val res: Map[Long, Double] = allMins.foldLeft(Map[Long, Double]())(
          (m, minute) => m + (minute -> workloadsByMinute.getOrElse(minute, 0d)))
        queue.queueName -> res.toSeq.sortBy(_._1).map(_._2).toList
      }).toMap

    queueWorkloadsByMinute
  }
  val colors = IndexedSeq("red", "blue")
  def chartDatas(workload: Workloads): Seq[ChartDataset] = {
    chartDataFromWorkloads(workload.workloads).zipWithIndex.map{
      case (qd, idx) => ChartDataset(qd._2, qd._1, borderColor = colors(idx))}.toSeq
  }

  //  private val workload: Seq[Double] = Iterator.continually(Random.nextDouble() * 250).take(numberOf15Mins).toSeq
  def ChartProps(labels: Seq[String], workload: Workloads) = {
    //    log.debug(s"Workload is ${workload}")
    val chartData = chartDatas(workload)
    Chart.ChartProps(
      "Workloads",
      Chart.LineChart,
      ChartData(labels, chartData)
    )
  }


  def mounted(props: Props) = {
    log.info("backend mounted")
    val cb: Callback = Callback.when(props.dashboardModelProxy().workloads.isEmpty) {
      props.dashboardModelProxy.dispatch(GetWorkloads("", "", "edi"))
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
        <.h2("Dashboard"),
        state.simulationResultWrapper(srw =>
          state.workloadsWrapper(workloadsModelProxy => {
            val workloads: Pot[Workloads] = workloadsModelProxy.value
            <.div(
              workloads.renderFailed(t => <.p(t.toString())),
              workloads.renderReady(wl => Chart(ChartProps(wl.labels, wl))),
              workloads.renderPending((num) => <.div(s"waiting for workloads with ${num}")),
              workloads.renderEmpty(<.div(s"Waiting for workload")))
          })),
        state.workloadsWrapper(workloadsModelProxy =>
          state.crunchResultWrapper(s =>
            DeskRecsChart(props.dashboardModelProxy)))
      )
    }
    .componentDidMount(scope => mounted(scope.props))
    .build

  def apply(router: RouterCtl[Loc], dashboardProps: ModelProxy[DashboardModels]) = component(Props(router, dashboardProps))
}

object DashboardQueue {

  case class Props(queueName: String,
                   workloads: Workloads
                  )

  val component = ReactComponentB[Props]("")
}