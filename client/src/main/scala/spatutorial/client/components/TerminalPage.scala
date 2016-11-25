package spatutorial.client.components

import diode.data.Ready
import diode.react.{ModelProxy, ReactConnectProxy}
import japgolly.scalajs.react.{BackendScope, ReactComponentB, ReactElement}
import japgolly.scalajs.react.extra.router.RouterCtl
import spatutorial.client.SPAMain.{Loc, TerminalLoc}
import spatutorial.client.components.Heatmap.Series
import spatutorial.client.services.SPACircuit

import japgolly.scalajs.react.vdom.ReactTagOf
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.logger._

object TerminalPage {

  case class Props(routeData: TerminalLoc, ctl: RouterCtl[Loc])

  class Backend($: BackendScope[Props, Unit]) {
    def render(props: Props) = {
      <.div(
        heatmapStuff(),
        TableTerminalDeskRecs.buildTerminalUserDeskRecsComponent(props.routeData.id)
      )
    }
  }

  def heatmapStuff() = {

    val seriiRCP: ReactConnectProxy[List[Series]] = SPACircuit.connect(_.queueCrunchResults.flatMap {
      case (terminalName, queueCrunchResult) =>
        queueCrunchResult.collect {
          case (queueName, Ready((Ready(crunchResult), _))) =>
            val series = Heatmap.getSeries(crunchResult)
            Series(terminalName + "/" + queueName, series.map(_.toDouble))
        }
    }.toList)
    seriiRCP((serMP: ModelProxy[List[Series]]) => {
      Heatmap.heatmap(Heatmap.Props(series = serMP(), height = 200, scaleFunction = Heatmap.bucketScale(20)))
    })
  }

  def apply(terminal: TerminalLoc, ctl: RouterCtl[Loc]): ReactElement =
    component(Props(terminal, ctl))

  private val component = ReactComponentB[Props]("Product")
    .renderBackend[Backend]
    .build
}
