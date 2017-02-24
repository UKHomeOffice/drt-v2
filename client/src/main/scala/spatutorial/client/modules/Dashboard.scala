package spatutorial.client.modules

import diode.data.Pot
import diode.react._
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.SPAMain.{Loc, UserDeskRecommendationsLoc}
import spatutorial.client.TableViewUtils
import spatutorial.client.components.Chart.ChartProps
import spatutorial.client.components._
import spatutorial.client.modules.GriddleComponentWrapper.ColumnMeta
import spatutorial.client.services.HandyStuff.PotCrunchResult
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.{Flights, QueueName, QueuePaxAndWorkLoads, TerminalName}
import spatutorial.shared._

import scala.collection.immutable
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, ScalaJSDefined}
import scala.util.{Random, Try}
import scala.language.existentials
import spatutorial.client.logger._

import scala.Iterable
import scala.collection.immutable.{Iterable, NumericRange}

object Dashboard {
  type QueueCrunchResults = Map[QueueName, Pot[PotCrunchResult]]
  type QueueSimulationResults = Map[QueueName, Pot[SimulationResult]]
  type QueueUserDeskRecs = Map[String, Pot[DeskRecTimeSlots]]

  case class DashboardModels(workloads: Pot[Workloads],
                             queueCrunchResults: Map[TerminalName, QueueCrunchResults],
                             potSimulationResult: Map[TerminalName, QueueSimulationResults]
                            )

  case class Props(router: RouterCtl[Loc], // proxy: ModelProxy[Pot[String]],
                   dashboardModelProxy: ModelProxy[DashboardModels],
                   airportConfigPot: Pot[AirportConfig]
                  )


  case class State(workloadsWrapper: ReactConnectProxy[Pot[Workloads]],
                   crunchResultWrapper: ReactConnectProxy[Map[TerminalName, QueueCrunchResults]],
                   simulationResultWrapper: ReactConnectProxy[Map[TerminalName, QueueSimulationResults]]
                  )

  def chartDataFromWorkloads(workloads: Map[String, QueuePaxAndWorkLoads], minutesPerGroup: Int = 15): Map[String, List[Double]] = {
    val startFromMilli = WorkloadsHelpers.midnightBeforeNow()
    val minutesRangeInMillis: NumericRange[Long] = WorkloadsHelpers.minutesForPeriod(startFromMilli, 24)
    val queueWorkloadsByMinute = WorkloadsHelpers.workloadPeriodByQueue(workloads, minutesRangeInMillis)
    val by15Minutes = queueWorkloadsByMinute.mapValues(
      (v) => v.grouped(minutesPerGroup).map(_.sum).toList
    )
    by15Minutes
  }

  val colors = IndexedSeq("red", "blue", "green", "black")


  def ChartProps(labels: Seq[String], chartData: Seq[ChartDataset], yAxisLabel: String) = {
    Chart.ChartProps(
      "Workloads",
      Chart.LineChart,
      ChartData(labels, chartData),
      yAxisLabel = yAxisLabel
    )
  }

  def chartDatas(workload: Workloads, terminalName: String): Seq[ChartDataset] = {
    chartDataFromWorkloads(workload.workloads(terminalName)).zipWithIndex.map {
      case (qd, idx) => ChartDataset(qd._2, qd._1, borderColor = colors(idx))
    }.toSeq
  }

  def mounted(props: Props) = {
    log.info("backend mounted")
    Callback.when(props.dashboardModelProxy().workloads.isEmpty) {
      props.dashboardModelProxy.dispatch(GetWorkloads("", ""))
    }
  }

  // create the React component for Dashboard
  private val component = ReactComponentB[Props]("Dashboard")
    // create and store the connect proxy in state for later use
    .initialState_P(props => State(
    props.dashboardModelProxy.connect(m => m.workloads),
    props.dashboardModelProxy.connect(m => m.queueCrunchResults),
    props.dashboardModelProxy.connect(m => m.potSimulationResult)
  ))
    .renderPS { (_, props, state: State) =>
      //      log.info(s"evaluating dashboard ${props}:${state}")
      <.div(
        <.h2("Dashboard"),
        state.simulationResultWrapper(srw =>
          state.workloadsWrapper(workloadsModelProxy => {
            val workloads: Pot[Workloads] = workloadsModelProxy.value
            <.div(
              workloads.renderFailed(t => <.p(t.toString())),
              workloads.renderReady(wl => {
                //                log.info(s"dashboard render ready ${wl.workloads("A1")}")
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
                        val every15th: Seq[String] = TableViewUtils.takeEveryNth(15)(wl.labels)
                        val datas: Seq[ChartDataset] = chartDatas(wl, terminalName)
                        val props1: ChartProps = ChartProps(every15th, datas, "Workloads")
                        <.div(
                          <.a(^.name := terminalName),
                          <.h2(terminalName),
                          Chart(props1))
                      })
                  )
                )
              }),
              workloads.renderPending((num) => <.div(s"waiting for workloads with ${num}")),
              workloads.renderEmpty(<.div(s"Waiting for paxload")))
          })))
    }
    .componentDidMount(scope => mounted(scope.props))
    .build

  def apply(router: RouterCtl[Loc], dashboardProps: ModelProxy[DashboardModels], airportConfigPot: Pot[AirportConfig]) = component(Props(router, dashboardProps, airportConfigPot))
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
