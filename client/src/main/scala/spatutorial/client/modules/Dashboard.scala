package spatutorial.client.modules

import diode.data.Pot
import diode.react._
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.SPAMain.{Loc, TodoLoc}
import spatutorial.client.components._
import scala.scalajs.js
import scala.util.Random
import scala.language.existentials

object Dashboard {

  case class Props(router: RouterCtl[Loc], proxy: ModelProxy[Pot[String]])

  case class State(motdWrapper: ReactConnectProxy[Pot[String]])
  val baseDate = new js.Date(2016, 10, 1, 7)
  val millisPer15Minutes = 1000 * 60 * 15
  val numberOf15Mins = 96
  // create dummy data for the chart
  val labelsDates = (baseDate.getTime() to (baseDate.getTime() + millisPer15Minutes * numberOf15Mins) by millisPer15Minutes).map(new js.Date(_))
  val labels = labelsDates.map(_.toTimeString())
  val cp = Chart.ChartProps(
    "Test chart",
    Chart.LineChart,
    ChartData(
      labels,
      Seq(ChartDataset(Iterator.continually(Random.nextDouble() * 250).take(numberOf15Mins).toSeq, "Workload"))
    )
  )
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

  // create the React component for Dashboard
  private val component = ReactComponentB[Props]("Dashboard")
    // create and store the connect proxy in state for later use
    .initialState_P(props => State(props.proxy.connect(m => m)))
    .renderPS { (_, props, state) =>
      <.div(
        // header, MessageOfTheDay and chart components
        <.h2("Dashboard"),
        state.motdWrapper(Motd(_)),
        Chart(cp),
        // create a link to the To Do view
        <.div(props.router.link(TodoLoc)("Check your todos!"))
      )
    }
    .build

  def apply(router: RouterCtl[Loc], proxy: ModelProxy[Pot[String]]) = component(Props(router, proxy))
}
