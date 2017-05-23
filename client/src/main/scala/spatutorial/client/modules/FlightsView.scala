package drt.client.modules

import diode.data.Pot
import diode.react.ReactConnectProxy
import drt.client.components.Bootstrap.Panel
import drt.client.components._
import drt.client.modules.GriddleComponentWrapper.ColumnMeta
import drt.shared.AirportInfo
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import japgolly.scalajs.react._
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.raw.ReactComponentUntyped
import japgolly.scalajs.react.vdom.TopNode
//import japgolly.scalajs.react.vdom.all.{VdomAttr => _, TagMod => _, _react_attrString => _, _react_autoRender => _, _react_fragReactNode => _}
import japgolly.scalajs.react.vdom.html_<^._

import scala.language.existentials
import scala.scalajs.js
import scala.scalajs.js.annotation.ScalaJSDefined
import scala.scalajs.js.{JSON, Object}

object GriddleComponentWrapper {

  @ScalaJSDefined
  class ColumnMeta(val columnName: String,
                   val order: js.UndefOr[Int] = js.undefined,
                   val cssClassName: js.UndefOr[String] = js.undefined,
                   val customComponent: Any = null) extends js.Object

}

@ScalaJSDefined
class RowMetaData(val key: String) extends js.Object

case class GriddleComponentWrapper[A](
                                    results: js.Array[A], //Seq[Map[String, Any]],
                                    columns: Seq[String],
                                    columnMeta: Option[Seq[ColumnMeta]] = None,
                                    rowMetaData: js.UndefOr[RowMetaData] = js.undefined,
                                    showSettings: Boolean = true,
                                    showFilter: Boolean = true,
                                    initialSort: js.UndefOr[String] = js.undefined
                                  ) {
  def toJS = {
    val p = js.Dynamic.literal(
      results = results,
      columns = columns,
      showSettings = showSettings,
      showFilter = showFilter,
      initialSort = initialSort,
      rowMetadata = rowMetaData,
      useFixedHeader = false,
      showPager = true,
      resultsPerPage = 200)
    (columnMeta).foreach { case cm => p.updateDynamic("columnMetadata")(cm) }

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

  //  def customColumn = ScalaComponent.builder[String].render(
  //    (s) =>  <.p("custom here")
  //  )
  def apply(children: VdomNode*) = {
    val f = WebpackRequire.React.asInstanceOf[js.Dynamic].createFactory(js.Dynamic.global.Bundle.griddle) // access real js component , make sure you wrap with createFactory (this is needed from 0.13 onwards)
    f(toJS, children).asInstanceOf[ReactComponentUntyped]
  }

}

object FlightsView {

  import japgolly.scalajs.react._
  import japgolly.scalajs.react.vdom.all.{onChange => _, _}

  import scala.language.existentials

  case class Props(
                    flightsModelProxy: Pot[FlightsWithSplits],
                    airportInfoProxy: Map[String, Pot[AirportInfo]],
                    activeCols: List[String] = List(
                      "IATA",
                      "Operator",
                      "Origin",
                      "Gate",
                      "Stand",
                      "Status",
                      "Sch",
                      "Est",
                      "Act",
                      "Chox",
                      "MaxPax",
                      "ActPax",
                      "Terminal"
                    )
                  )

  case class State(
                    flights: ReactConnectProxy[Pot[Flights]],
                    airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]]
                  )

  val component = ScalaComponent.builder[Props]("Flights")
    .render_P((props) => {
      <.div(
        <.h2("Flights"),
        Panel(
          Panel.Props("Flights"),
          FlightsTable(props)
        )
      )
    }).build

  def apply(props: Props): Unmounted[Props, Unit, Unit] = component(props)
}


object FlightsWithSplitsView {

  import drt.shared.DeskAndPaxTypeCombinations._
  import japgolly.scalajs.react._
  import japgolly.scalajs.react.vdom.all.{onChange => _, _}

  import scala.language.existentials
  case class Props(
                    flightsModelProxy: Pot[List[js.Dynamic]],
                    airportInfoProxy: Map[String, Pot[AirportInfo]],
                    activeCols: List[String] = List(
                      "Timeline",
                      "Flight",
                      "Origin",
                      "Gate",
                      "Stand",
                      "Status",
                      "Sch",
                      "Est",
                      "Act",
                      "Est Chox",
                      "Act Chox",
                      "Pax",
                      "Splits"
                    ))

  case class State(
                    flights: ReactConnectProxy[Pot[FlightsWithSplits]],
                    airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]]
                  )

  val component = ScalaComponent.builder[Props]("FlightsWithSplits")
    .render_P((props) => {
      <.div(
        Panel(Panel.Props(""), FlightsWithSplitsTable(props))
      )
    }).build

  def apply(props: Props) = component(props)
}

