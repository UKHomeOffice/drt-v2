package spatutorial.client.components

import diode.data.Pot
import diode.react.ModelProxy
import japgolly.scalajs.react.{Callback, ReactComponentB}
import spatutorial.client.components.Bootstrap.{CommonStyle, Button, Panel}
import spatutorial.client.services.{ChangeDeskUsage, UpdateCrunch, UpdateMotd}
import spatutorial.shared.CrunchResult
import spatutorial.client.logger._
import diode.data.Pot
import diode.react.ReactPot._
import diode.react._
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.components.Bootstrap._
import spatutorial.client.logger._
import spatutorial.shared.CrunchResult
import japgolly.scalajs.react.vdom.DomCallbackResult._

object DeskRecsChart {
  log.info("initialising deskrecschart")

  def DeskRecs(labels: Seq[String]) = ReactComponentB[ModelProxy[Pot[CrunchResult]]]("CrunchResults")
    .render_P { proxy =>
      Panel(Panel.Props("Desk Recommendations and Wait times"),
        proxy().renderPending(_ >= 500, _ => <.p("Waiting for data")),
        proxy().render(chartData =>
          Chart(Chart.ChartProps("Desk Recs",
            Chart.LineChart,
            ChartData(takeEvery15th(labels), Seq(
              ChartDataset(
                takeEvery15th(chartData.recommendedDesks), "Desk Recommendations")))
          ))),
        proxy().render(chartData => {
          val sampledWaitTimes = chartData.waitTimes.grouped(15).map(_.max).toList
          val sampledLabels = takeEvery15th(labels)
          Chart(Chart.ChartProps("Wait Times",
            Chart.LineChart,
            ChartData(sampledLabels, Seq(ChartDataset(sampledWaitTimes, "Wait Times")))
          ))
        }),
        proxy().render(crunchResult => {
          def inputChange(idx: Int)(e: ReactEventI) = {
            val ev = e.target.value
            val accessKey = e.target.accessKey
            log.info(s"direct call in callback ${idx} ${ev}")
            Callback.log(s"callback from outside", ev, accessKey, idx)
            proxy.dispatch(ChangeDeskUsage(ev, idx))
            //    CallbackTo[Unit](() => log.info(s"Got a change! ${e}"))
          }

          val rds = takeEvery15th(crunchResult.recommendedDesks)
          val skippedLabels = takeEvery15th(labels)
          val zip: Seq[((String, Double), Int)] = skippedLabels.zip(rds).zipWithIndex
          <.ul(zip.map { case (dr, idx) => {
            <.li(<.span(dr._1.toString(),
              <.input.number(^.value := dr._2,
                ^.key := dr._1.toString(),
                ^.onChange ==> inputChange(idx))))
          }
          })
        }),
        Button(Button.Props(proxy.dispatch(UpdateCrunch()), CommonStyle.danger), Icon.refresh, "Update")
      )
    }.componentDidMount(scope =>
    Callback.log("Mounted DeskRecs")
  ).build

  def takeEvery15th[N](desks: Seq[N]) = desks.zipWithIndex.collect {
    case (n, i) if (i % 15 == 0) => n
  }

  def apply(labels: Seq[String], proxy: ModelProxy[Pot[CrunchResult]]) = DeskRecs(labels)(proxy)
}
