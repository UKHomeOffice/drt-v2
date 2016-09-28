package spatutorial.client.modules

import chandu0101.scalajs.react.components.ReactTable
import diode.react.ReactPot._
import diode.data.Pot
import diode.react._
import diode.util._
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.Frag
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.SPAMain.{Loc, UserDeskRecommendationsLoc}
import spatutorial.client.components.Bootstrap.Panel
import spatutorial.client.components._
import spatutorial.client.modules.GriddleComponentWrapper.ColumnMeta
import spatutorial.client.services.HandyStuff.CrunchResultAndDeskRecs
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.{Flights, QueueName, QueueWorkloads}
import spatutorial.shared._

import scala.collection.immutable
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, ScalaJSDefined}
import scala.util.{Try, Random}
import scala.language.existentials
import spatutorial.client.logger._
import scala.collection.immutable.Iterable

//@JSExport
//object Ouch {
//  @JSExport("mySimpleComp")
//  def mySimpleComp(props: js.Dynamic) =  {
//    log.info(s"simple comp ${props}")
//    <.p(props.toString())
//  }
//  val justAFunc: js.Function = (props: ColumnMetaProps) => {
//    log.info(s"simple comp ${props}")
//    <.h1("Mineminemine", <.input.text(^.value := props.data.toString())).render
//  }
//  val reactWrappedFunc = js.Dynamic.global.React.createFactory(justAFunc)
//}
//object MyTestGrid {
//
//  @js.native
//  class ColumnMetaProps(val data: js.Object, val rowData: js.Object, val metadata: js.Object) extends js.Object
//
//  //  object - the data that would normally be rendered in the column.
//  //    rowData
//  //  object - the data for all items in the same row
//  //    metadata
//  //  object - The columnMetadata object)
//
//  @JSExport
//  val testComp = ReactComponentB[js.Dynamic]("Mine")
//    .initialState_P((p) => {
//      log.info(s"initialSate ${p.data}")
//      new ColumnMetaProps(p.data.asInstanceOf[js.Object], p.rowData.asInstanceOf[js.Object], p.metadata.asInstanceOf[js.Object])
//    }
//  ).renderP(
//    (sc, props: js.Dynamic) => {
//      //      log.info(s"what scope is ${sc.props}")
//      log.info(s"what is ${sc.props}")
//      log.info(s"what is $props")
//      //      val string: Frag = if (!js.isUndefined(props)) props.data.toString() else "nothing!"
//      val localsc = sc
//      log.info(s"localsc ${localsc}")
//      val localscprops = Try(sc.props)
//      log.info(s"localscprops ${localsc.props.toString()}")
//
//      val localscpropsdata = localscprops.map(p => p.data)
//      log.info(s"localdata is ${localscpropsdata}")
//      val string = localscprops.toString() //Try(sc.props.data).toString()
//      <.h1(string)
//    })
//    .componentDidMount((scope) => Callback.log(s"did mount ${js.Object.getOwnPropertyNames(scope)} ${scope.props}"))
//    .componentWillReceiveProps((whatIsThis) => {
//      Callback.log(s"willReceiveProps ${whatIsThis}")
//    })
//    .build
//
//  val simpleComp = ReactComponentB[Unit]("SimpleThing").render((p) => <.p("haha I work")).build
//
//  //  val columnMeta = (new ColumnMeta("c1", 1, testComp.reactClass) :: Nil).toJsArray
//  val columnMeta = (new ColumnMeta("c1", 1, Ouch.justAFunc) :: Nil).toJsArray
//
//
//  val results = Seq(
//    js.Dynamic.literal("c1" -> "aa", "c2" -> "bb")
//
//  ).toJsArray
//
//  @ScalaJSDefined
//  class Row(val c1: String, val c2: String) extends js.Object
//
//  val component = ReactComponentB[js.Dynamic]("MyTestGrid")
//    .render_P {
//      props =>
//        GriddleComponentWrapper(results, columns = "c1" :: "c2" :: Nil, columnMeta = Some(columnMeta))()
//    }.build
//
//  def apply() = component
//}


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


  def ChartProps(labels: Seq[String], workload: Workloads) = {
    val chartData: Seq[ChartDataset] = chartDatas(workload)
    Chart.ChartProps(
      "Workloads",
      Chart.LineChart,
      ChartData(labels, chartData)
    )
  }

  def chartDatas(workload: Workloads): Seq[ChartDataset] = {
    chartDataFromWorkloads(workload.workloads).zipWithIndex.map {
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
              workloads.renderReady(wl => Chart(ChartProps(DeskRecsChart.takeEvery15th(wl.labels), wl))),
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