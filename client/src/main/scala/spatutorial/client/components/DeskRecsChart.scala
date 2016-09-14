package spatutorial.client.components

import diode.Action
import diode.data.Pot
import diode.react.ModelProxy
import diode.react.ReactPot._
import japgolly.scalajs.react.vdom.DomCallbackResult._
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{Callback, ReactComponentB, _}
import spatutorial.client.components.Bootstrap.{Button, CommonStyle, Panel}
import spatutorial.client.logger._
import spatutorial.client.modules.Dashboard.DashboardModels
import spatutorial.client.services.{ChangeDeskUsage, Crunch}
import spatutorial.shared.CrunchResult

object DeskRecsChart {
  type DeskRecsModel = DashboardModels

  log.info("initialising deskrecschart")

  def DeskRecs(labels: IndexedSeq[String]) = ReactComponentB[ModelProxy[DeskRecsModel]]("CrunchResults")
    .render_P { proxy => {
      val potCrunchResult: Pot[CrunchResult] = proxy().potCrunchResult
      val dispatch: (Action) => Callback = proxy.dispatch _
      val workloads = proxy().workloads
      Panel(Panel.Props("Desk Recommendations and Wait times"),
        potCrunchResult.renderPending(_ >= 500, _ => <.p("Waiting for data")),
        deskRecsChart(labels, potCrunchResult),
        waitTimesChart(labels, potCrunchResult),
        deskSimulationInputs(labels, potCrunchResult, dispatch),
        workloads.render( wl => Button(Button.Props(dispatch(Crunch(wl.workloads)), CommonStyle.danger), Icon.refresh, "Update"))
      )
    }
    }.componentDidMount(scope =>
    Callback.log("Mounted DeskRecs")
  ).build

  def deskSimulationInputs(labels: IndexedSeq[String], potCrunchResult: Pot[CrunchResult], dispatch: Action => Callback): ReactNode = {
    def inputChange(idx: Int)(e: ReactEventI) = {
      val ev = e.target.value
      log.info(s"direct call in callback ${idx} ${ev}")
      Callback.log(s"callback from outside", ev, idx)
      dispatch(ChangeDeskUsage(ev, idx))
    }

    potCrunchResult.render(crunchResult => {
      val rds = takeEvery15th(crunchResult.recommendedDesks)
      val skippedLabels = takeEvery15th(labels)
      val zip: Seq[((String, Int), Int)] = skippedLabels.zip(rds).zipWithIndex
      <.ul(zip.map { case (dr, idx) => {
        <.li(<.span(dr._1.toString(),
          <.input.number(^.value := dr._2,
            ^.onChange ==> inputChange(idx))))
      }
      })
    })
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
