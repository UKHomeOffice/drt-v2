package spatutorial.client.modules

import diode.data.Pot
import diode.react._
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.SPAMain.{Loc, UserDeskRecommendationsLoc}
import spatutorial.client.components.Chart.ChartProps
import spatutorial.client.components._
import spatutorial.client.modules.GriddleComponentWrapper.ColumnMeta
import spatutorial.client.services.HandyStuff.CrunchResultAndDeskRecs
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.{Flights, QueueName, QueueWorkloads, TerminalName}
import spatutorial.shared._

import scala.collection.immutable
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, ScalaJSDefined}
import scala.util.{Random, Try}
import scala.language.existentials
import spatutorial.client.logger._

import scala.Iterable
import scala.collection.immutable.Iterable

object Dashboard {
  type QueueCrunchResults = Map[QueueName, Pot[CrunchResultAndDeskRecs]]
  type QueueSimulationResults = Map[QueueName, Pot[SimulationResult]]
  type QueueUserDeskRecs = Map[String, Pot[UserDeskRecs]]

  case class DashboardModels(workloads: Pot[Workloads],
                             queueCrunchResults: QueueCrunchResults,
                             potSimulationResult: QueueSimulationResults,
                             potUserDeskRecs: QueueUserDeskRecs
                            )

  case class Props(router: RouterCtl[Loc], // proxy: ModelProxy[Pot[String]],
                   dashboardModelProxy: ModelProxy[DashboardModels]
                  )


  case class State(workloadsWrapper: ReactConnectProxy[Pot[Workloads]],
                   crunchResultWrapper: ReactConnectProxy[QueueCrunchResults],
                   simulationResultWrapper: ReactConnectProxy[QueueSimulationResults],
                   userDeskRecsWrapper: ReactConnectProxy[QueueUserDeskRecs]
                  )

  def chartDataFromWorkloads(workloads: Map[String, QueueWorkloads]): Map[String, List[Double]] = {
    val timesMin = workloads.values.flatMap(_._2.map(c => c.time)).min
    val oneMin = 6000L
    val allMins = timesMin until (timesMin + oneMin * 60 * 24) by oneMin
    val queueWorkloadsByMinute = workloads
      .mapValues(queue => {
        val minute0 = queue._1
        val minute1 = WorkloadsHelpers.workloadsByPeriod(queue._1, 15).toList
        log.info(s"by 1 mins: ${minute0.take(40)}")
        log.info(s"by 15 mins: ${minute1}")
        val workloadsByMinute = minute0.map((wl) => (wl.time, wl.workload)).toMap
        val res: Map[Long, Double] = allMins.foldLeft(Map[Long, Double]())(
          (m, minute) => m + (minute -> workloadsByMinute.getOrElse(minute, 0d)))
        res.toSeq.sortBy(_._1).map(_._2)
          .grouped(15).map(_.sum)
          .toList
      })

    queueWorkloadsByMinute
  }

  val colors = IndexedSeq("red", "blue")


  def ChartProps(labels: Seq[String], chartData: Seq[ChartDataset]) = {
    Chart.ChartProps(
      "Workloads",
      Chart.LineChart,
      ChartData(labels, chartData)
    )
  }

  def chartDatas(workload: Workloads, terminalName: String): Seq[ChartDataset] = {
    log.info(s"Looking up $terminalName in ${workload.workloads.keys}")
    chartDataFromWorkloads(workload.workloads(terminalName)).zipWithIndex.map {
      case (qd, idx) => ChartDataset(qd._2, qd._1, borderColor = colors(idx))
    }.toSeq
  }

  def mounted(props: Props) = {
    log.info("backend mounted")
    Callback.when(props.dashboardModelProxy().workloads.isEmpty) {
      props.dashboardModelProxy.dispatch(GetWorkloads("", "", "edi"))
    }
  }

  // create the React component for Dashboard
  private val component = ReactComponentB[Props]("Dashboard")
    // create and store the connect proxy in state for later use
    .initialState_P(props => State(
    props.dashboardModelProxy.connect(m => m.workloads),
    props.dashboardModelProxy.connect(m => m.queueCrunchResults),
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
              workloads.renderReady(wl => {
                val keys: Seq[TerminalName] = wl.workloads.keys.toList
                <.div(
                  <.ul(^.cls := "nav nav-pills",
                    keys.map(
                      terminalName => <.li(<.a(^.cls := "active",
                        ^.href := s"#$terminalName",
                        terminalName)))
                  ),
                  <.div(
                    keys.map(
                      terminalName => {
                        log.info(s"YEah!!! $terminalName")
                        val every15th: Seq[String] = DeskRecsChart.takeEvery15th(wl.labels)
                                              val datas: Seq[ChartDataset] = chartDatas(wl, terminalName)
                                              val props1: ChartProps = ChartProps(every15th, datas)
                                              <.div(
                                                <.a(^.name := terminalName),
                                                <.h2(terminalName),
                                                Chart(props1),
                                                state.crunchResultWrapper(s =>
                                                  DeskRecsChart(props.dashboardModelProxy)))
                      })
                  )
                )
              }),
              workloads.renderPending((num) => <.div(s"waiting for workloads with ${num}")),
              workloads.renderEmpty(<.div(s"Waiting for workload")))
          })))
    }
    .componentDidMount(scope => mounted(scope.props))
    .build

  def apply(router: RouterCtl[Loc], dashboardProps: ModelProxy[DashboardModels]) = component(Props(router, dashboardProps))
}

// object DashboardTerminals {
//  val component = ReactComponentB[Props]

// }

object DashboardQueue {

  case class Props(queueName: String,
                   workloads: Workloads
                  )

  val component = ReactComponentB[Props]("")
}
