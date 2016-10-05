package spatutorial.client.modules

import spatutorial.client.modules.GriddleComponentWrapper.ColumnMeta
import sun.java2d.loops.CustomComponent

import scala.scalajs.js.annotation.{JSExport, JSExportAll, ScalaJSDefined}
import scala.scalajs.js.{JSON, Object}
import chandu0101.scalajs.react.components.ReactTable.Backend
import chandu0101.scalajs.react.components.{JsonUtil, ReactTable, Spinner}
import chandu0101.scalajs.react.components.materialui.{DeterminateIndeterminate, MuiCircularProgress, MuiPaper, ZDepth}
import com.payalabs.scalajs.react.bridge.ReactBridgeComponent
import com.sun.org.apache.xpath.internal.operations.Bool
import diode.data.{Pot, Ready}
import diode.react.{ModelProxy, ReactConnectProxy, ReactPot}
import japgolly.scalajs.react.ReactComponentB
import japgolly.scalajs.react.extra.router.RouterCtl
import spatutorial.client.SPAMain.Loc
import spatutorial.client.components.Bootstrap.Panel
import spatutorial.client.services.RequestFlights
import spatutorial.shared.{AirportInfo, ApiFlight, CrunchResult, SimulationResult}
import spatutorial.shared.FlightsApi.Flights
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.logger._
import diode.react.ReactPot._
import diode.react._
import diode.util._
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.SPAMain.{Loc, UserDeskRecommendationsLoc}
import spatutorial.client.components.Bootstrap.Panel
import spatutorial.client.components._
import spatutorial.client.services.{Crunch, GetWorkloads, Workloads}
import spatutorial.shared.FlightsApi.Flights
import com.payalabs.scalajs.react.bridge._
import japgolly.scalajs.react.vdom.all.{ReactAttr => _, TagMod => _, _react_attrString => _, _react_autoRender => _, _react_fragReactNode => _, _}

import scala.scalajs.js
import scala.util.Random
import scala.language.existentials
import spatutorial.client.logger._

object GriddleComponentWrapper {

  @ScalaJSDefined
  class ColumnMeta(val columnName: String, val order: js.UndefOr[Int] = js.undefined, val customComponent: Any = null) extends js.Object

}

@ScalaJSDefined
class RowMetaData(val key: String) extends js.Object

case class GriddleComponentWrapper(
  results: js.Any, //Seq[Map[String, Any]],
  columns: Seq[String],
  columnMeta: Option[Seq[ColumnMeta]] = None,
  rowMetaData: js.UndefOr[RowMetaData] = js.undefined,
  showSettings: Boolean = true,
  showFilter: Boolean = true
) {
  def toJS = {
    val p = js.Dynamic.literal()
    p.updateDynamic("results")(results)
    p.updateDynamic("columns")(columns)
    p.updateDynamic("showSettings")(showSettings)
    p.updateDynamic("showFilter")(showFilter)
    p.updateDynamic("rowMetadata")(rowMetaData)
    p.updateDynamic("showPager")(false)
    (columnMeta).foreach { case cm => p.updateDynamic("columnMetadata")(cm.toJsArray) }

    fixWeirdCharacterEncoding(p)

    val meta =
      """[
        |{"columnName": "Origin","visible": true, "order": 2}
        |]""".stripMargin
    val parsedMeta = JSON.parse(meta, null)
    p.updateDynamic("columnMeta")(parsedMeta)
    p
  }

  def fixWeirdCharacterEncoding(p: Object with js.Dynamic): Unit = {
    // these are here because of a weird character encoding issue when twirl bundles bundle.js into client-jsdeps.js
    p.updateDynamic("sortAscendingComponent")("  ▲")
    p.updateDynamic("sortDescendingComponent")(" ▼")
  }

  //  def customColumn = ReactComponentB[String].render(
  //    (s) =>  <.p("custom here")
  //  )
  def apply(children: ReactNode*) = {
    val f = React.asInstanceOf[js.Dynamic].createFactory(js.Dynamic.global.Bundle.griddle) // access real js component , make sure you wrap with createFactory (this is needed from 0.13 onwards)
    f(toJS, children.toJsArray).asInstanceOf[ReactComponentU_]
  }

}

object FlightsView {

  import japgolly.scalajs.react.vdom.all.{onChange => _, _}
  import japgolly.scalajs.react._
  import scala.scalajs.js
  import scala.language.existentials

  case class Props(
    flightsModelProxy: Pot[Flights],
    airportInfoProxy: Map[String, Pot[AirportInfo]]
  )

  case class State(
    flights: ReactConnectProxy[Pot[Flights]],
    airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]]
  )

  val component = ReactComponentB[Props]("Flights")
    .render_P((props) => {
      <.div(
        <.h2("Flights"),
        Panel(
          Panel.Props("Flights"),
          FlightsTable(props)
        )
      )
    }).build

  def apply(props: Props): ReactComponentU[Props, Unit, Unit, TopNode] = component(props)
}

