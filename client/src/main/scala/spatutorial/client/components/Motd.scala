package spatutorial.client.components

import diode.react.ReactPot._
import diode.react._
import diode.data.Pot
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.components.Bootstrap._
import spatutorial.client.services.{UpdateCrunch, Crunch, UpdateMotd}
import spatutorial.shared.CrunchResult
import spatutorial.client.logger._

/**
  * This is a simple component demonstrating how to display async data coming from the server
  */
object Motd {

  // create the React component for holding the Message of the Day
  val Motd = ReactComponentB[ModelProxy[Pot[String]]]("Motd")
    .render_P { (proxy: ModelProxy[Pot[String]]) =>
      val proxy1: Pot[String] = proxy()
      Panel(Panel.Props("Message of the day"),
        // render messages depending on the state of the Pot
        proxy1.renderPending(_ > 500, _ => <.p("Loading...")),
        proxy().renderFailed(ex => <.p("Failed to load")),
        proxy().render(m => <.p(m)),
        Button(Button.Props(proxy.dispatch(UpdateMotd()), CommonStyle.danger), Icon.refresh, " Update")
      )
    }
    .componentDidMount(scope =>
      // update only if Motd is empty
      Callback.when(scope.props.value.isEmpty)({
        println("Updating because it's empty")
        scope.props.dispatch(UpdateMotd())
      })
    )
    .build

  def apply(proxy: ModelProxy[Pot[String]]) = Motd(proxy)
}

object DeskRecsChart {
  log.info("initialising deskrecschart")

  def DeskRecs(labels: Seq[String]) = ReactComponentB[ModelProxy[Pot[CrunchResult]]]("CrunchResults")
    .render_P { (proxy) =>
      Panel(Panel.Props("CrunchResults"),
        proxy().renderPending(_ >= 500, _ => <.p("Waiting for data")),
        proxy().render(chartData =>
          Chart(Chart.ChartProps("Desk Recs",
            Chart.LineChart,
            ChartData(labels, Seq(ChartDataset(chartData.recommendedDesks, "Desk Recommendations")))
          ))
        ),
        Button(Button.Props(proxy.dispatch(UpdateCrunch()), CommonStyle.danger), Icon.refresh, " Update")
      )
    }.componentDidMount(scope =>
    Callback.log("Mounted DeskRecs")
  ).build

  def apply(labels: Seq[String], proxy: ModelProxy[Pot[CrunchResult]]) = DeskRecs(labels)(proxy)
}