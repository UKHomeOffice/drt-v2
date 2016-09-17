package spatutorial.client.components

import diode.Action
import diode.data.Pot
import diode.react.{ReactConnectProxy, ModelProxy}
import diode.react.ReactPot._
import japgolly.scalajs.react
import japgolly.scalajs.react.vdom.DomCallbackResult._
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{Callback, ReactComponentB, _}
import spatutorial.client.components.Bootstrap.Panel.Props
import spatutorial.client.components.Bootstrap.{Button, CommonStyle, Panel}
import spatutorial.client.logger._
import spatutorial.client.modules.Dashboard.DashboardModels
import spatutorial.client.services.{UserDeskRecs, RunSimulation, ChangeDeskUsage, Crunch}
import spatutorial.shared.{DeskRecTimeslot, DeskRec, CrunchResult, SimulationResult}

object DeskRecsChart {
  type DeskRecsModel = DashboardModels

  log.info("initialising deskrecschart")

  case class State(deskRecs: ReactConnectProxy[Pot[SimulationResult]])

  def DeskRecs(labels: IndexedSeq[String]) = ReactComponentB[ModelProxy[DeskRecsModel]]("CrunchResults")
    .render_P(deskRecsRender(labels))
    .componentDidMount(scope =>
      Callback.log("Mounted DeskRecs")
    ).build

  def deskRecsRender(labels: IndexedSeq[String]): (ModelProxy[DeskRecsModel]) => ReactComponentU[Props, Unit, Unit, react.TopNode] = {
    proxy => {
      log.info(s"rendering desk recs")
      val potCrunchResult: Pot[CrunchResult] = proxy().potCrunchResult
      val potSimulationResult: Pot[SimulationResult] = proxy().potSimulationResult
      val dispatch: (Action) => Callback = proxy.dispatch _
      val workloads = proxy().workloads
      Panel(Panel.Props("Desk Recommendations and Wait times"),
        potCrunchResult.renderPending(_ >= 500, _ => <.p("Waiting for data")),
        deskRecsChart(labels, potCrunchResult),
        waitTimesChart(labels, potCrunchResult),
        workloads.render(wl => Button(Button.Props(dispatch(Crunch(wl.workloads)), CommonStyle.danger), Icon.refresh, "Update"))
      )
    }
  }

  def DeskSimInputs(labels: IndexedSeq[String]) = ReactComponentB[ModelProxy[Pot[UserDeskRecs]]]("FunkyInputs")
    .renderPS {
      (_, proxy, state) => {
        val potSimulationResult = proxy()
        val dispatch: (Action) => Callback = proxy.dispatch _
        Panel(Panel.Props("Override Desk Recommendations and Wait times"),
          deskSimulationInputs(labels, potSimulationResult, dispatch)
        )
      }
    }.componentDidMount(scope =>
    Callback.log("Mounted Desk Sim Inputs")
  ).build

  def deskSimulationInputs(labels: IndexedSeq[String], potSimulationResult: Pot[UserDeskRecs], dispatch: Action => Callback): ReactNode = {
    def inputChange(idx: Int)(e: ReactEventI) = {
      val ev = e.target.value
      e.preventDefault()
      log.info(s"direct call in callback ${idx} ${ev}")
      Callback.log(s"callback from outside", ev, idx)
      dispatch(ChangeDeskUsage(ev, idx))
    }

    <.div(^.key := "inputs",
      potSimulationResult.renderEmpty(<.p("Waiting for simulation")),
      potSimulationResult.renderReady(rds => {
        log.info("rendering simulation inputs")
        val skippedLabels = takeEvery15th(labels)
        val zip: IndexedSeq[(String, DeskRecTimeslot)] = skippedLabels.zip(rds.items)
        <.ul(zip.map { case (label, dr) => {
          <.li(<.span(label,
            <.input.number(^.value := dr.deskRec.toString, ^.key := dr.id,
              ^.onChange ==> inputChange(dr.id.toInt))))
        }
        })
      }))
  }

  def waitTimesChart(labels: IndexedSeq[String], potCrunchResult: Pot[CrunchResult]): ReactNode = {
    potCrunchResult.render(chartData => {
      val grouped: Iterator[Seq[Int]] = chartData.waitTimes.grouped(15)
      val maxInEachGroup: Iterator[Int] = grouped.map(_.max)
      val sampledWaitTimes = maxInEachGroup.map(_.toDouble).toList
      val sampledLabels = takeEvery15th(labels)
      Chart(Chart.ChartProps("Wait Times",
        Chart.LineChart,
        ChartData(sampledLabels, Seq(ChartDataset(sampledWaitTimes, "Wait Times")))
      ))
    })
  }

  def userSimulationWaitTimesChart(labels: IndexedSeq[String],
                                   simulationResult: ModelProxy[Pot[SimulationResult]]) = {
    val component = ReactComponentB[ModelProxy[Pot[SimulationResult]]]("UserSimulationChart").render_P(proxy => {
      log.info("rendering chart")
      if (proxy().isReady) {
        log.info(s"Think our simulation result is ready! ${proxy()}")
        val grouped: Iterator[Seq[Int]] = proxy().get.waitTimes.grouped(15)
        val maxInEachGroup: Iterator[Int] = grouped.map(_.max)
        val sampledWaitTimes = maxInEachGroup.map(_.toDouble).toList
        val sampledLabels = takeEvery15th(labels)
        Chart(Chart.ChartProps("Simulated Wait Times",
          Chart.LineChart,
          ChartData(sampledLabels, Seq(ChartDataset(sampledWaitTimes, "Simulated Wait Times")))
        ))
      } else {
        <.p("waiting for data")
      }
    }).build
    component(simulationResult)
  }


  def deskRecsChart(labels: IndexedSeq[String], potCrunchResult: Pot[CrunchResult]): ReactNode = {
    potCrunchResult.render(chartData =>
      Chart(Chart.ChartProps("Desk Recs",
        Chart.LineChart,
        ChartData(takeEvery15th(labels), Seq(
          ChartDataset(
            takeEvery15th(chartData.recommendedDesks).map(_.toDouble), "Desk Recommendations")))
      )))
  }

  def takeEvery15th[N](desks: IndexedSeq[N]) = desks.zipWithIndex.collect {
    case (n, i) if (i % 15 == 0) => n
  }

  def apply(labels: IndexedSeq[String], proxy: ModelProxy[DeskRecsModel]) = DeskRecs(labels)(proxy)
}
