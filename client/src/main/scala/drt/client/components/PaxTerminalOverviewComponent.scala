package drt.client.components

import japgolly.scalajs.react.{Children, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport



@js.native
trait IPaxTerminalOverview extends js.Object {
  var terminal: String
  var periodLengthMinutes: Int
  var currentTime: String
  var desks: Int
  var staff: Int
  var flights: js.Array[Integer]
  var ragStatus: String
  var chartData: ChartData
  var pressure: js.Array[Pressure]
  var periodQueuePaxCounts: js.Array[PeriodQueuePaxCounts]
}

object IPaxTerminalOverview {
  def apply(
             terminal: String,
             periodLengthMinutes: Int,
             currentTime: String,
             desks: Int,
             staff: Int,
             flights: js.Array[Integer],
             ragStatus: String,
             chartData: ChartData,
             pressure: js.Array[Pressure],
             estimates: js.Array[PeriodQueuePaxCounts]
           ): IPaxTerminalOverview = {
    val p = (new js.Object).asInstanceOf[IPaxTerminalOverview]
    p.terminal = terminal
    p.periodLengthMinutes = periodLengthMinutes
    p.currentTime = currentTime
    p.desks = desks
    p.staff = staff
    p.flights = flights
    p.ragStatus = ragStatus
    p.chartData = chartData
    p.pressure = pressure
    p.periodQueuePaxCounts = estimates
    p
  }
}

@js.native
trait ChartData extends js.Object {
  var labels: js.Array[String]
  var datasets: js.Array[Dataset]
}

object ChartData {
  def apply(labels: js.Array[String], datasets: js.Array[Dataset]): ChartData = {
    val c = (new js.Object).asInstanceOf[ChartData]
    c.labels = labels
    c.datasets = datasets
    c
  }
}

@js.native
trait Dataset extends js.Object {
  var data: js.Array[Int]
  var backgroundColor: js.Array[String]
}

object Dataset {
  def apply(data: js.Array[Int], backgroundColor: js.Array[String]): Dataset = {
    val d = (new js.Object).asInstanceOf[Dataset]
    d.data = data
    d.backgroundColor = backgroundColor
    d
  }
}

@js.native
trait Pressure extends js.Object {
  var pressure: String
  var from: String
  var to: String
}

object Pressure {
  def apply(pressure: String, from: String, to: String): Pressure = {
    val p = (new js.Object).asInstanceOf[Pressure]
    p.pressure = pressure
    p.from = from
    p.to = to
    p
  }
}

@js.native
trait PeriodQueuePaxCounts extends js.Object {
  var from: String
  var to: String
  var egate: Int
  var eea: Int
  var noneea: Int
}

object PeriodQueuePaxCounts {
  def apply(from: String, to: String, egate: Int, eea: Int, noneea: Int): PeriodQueuePaxCounts = {
    val e = (new js.Object).asInstanceOf[PeriodQueuePaxCounts]
    e.from = from
    e.to = to
    e.egate = egate
    e.eea = eea
    e.noneea = noneea
    e
  }
}

object PaxTerminalOverviewComponent {

  @js.native
  @JSImport("@drt/drt-react", "PaxTerminalOverview")
  object RawComponent extends js.Object

  val component = JsFnComponent[IPaxTerminalOverview, Children.None](RawComponent)

  def apply(props: IPaxTerminalOverview): VdomElement = {
    component(props)
  }

}
