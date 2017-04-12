package drt.client.modules

import diode.data.Pot
import diode.react.ReactConnectProxy
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.all.{ReactAttr => _, TagMod => _, _react_attrString => _, _react_autoRender => _, _react_fragReactNode => _}
import japgolly.scalajs.react.vdom.prefix_<^._
import drt.client.components.Bootstrap.Panel
import drt.client.components._
import drt.client.modules.GriddleComponentWrapper.ColumnMeta
import drt.shared.AirportInfo
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}

import scala.language.existentials
import scala.scalajs.js
import scala.scalajs.js.annotation.ScalaJSDefined
import scala.scalajs.js.{JSON, Object}

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
      useFixedHeader = true,
      showPager = true,
      resultsPerPage = 200)
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

  import japgolly.scalajs.react._
  import japgolly.scalajs.react.vdom.all.{onChange => _, _}

  import scala.language.existentials

  case class Props(
                    flightsModelProxy: Pot[Flights],
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
                      "Act chox",
                      "MaxPax",
                      "ActPax",
                      "Terminal"
                    )
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


object FlightsWithSplitsView {

  import japgolly.scalajs.react._
  import japgolly.scalajs.react.vdom.all.{onChange => _, _}

  import scala.language.existentials

  import drt.shared.DeskAndPaxTypeCombinations._
  case class Props(
                    flightsModelProxy: Pot[List[js.Dynamic]],
                    airportInfoProxy: Map[String, Pot[AirportInfo]],
                    activeCols: List[String] = List(
                      "Flight",
                      "Origin",
                      "Gate",
                      "Stand",
                      "Status",
                      "Sch",
                      "Est",
                      "Act",
                      "Act chox",
                      "Pax",

                      "Terminal",

                      egate,
                      deskEeaNonMachineReadable,
                      deskEea,
                      nationalsDeskVisa,
                      nationalsDeskNonVisa
                    ))

  case class State(
                    flights: ReactConnectProxy[Pot[FlightsWithSplits]],
                    airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]]
                  )

  val component = ReactComponentB[Props]("FlightsWithSplits")
    .render_P((props) => {
      <.div(
        <.h2("Flights"),
        Panel(
          Panel.Props("Flights"),
          FlightsWithSplitsTable(props)
        )
      )
    }).build

  def apply(props: Props): ReactComponentU[Props, Unit, Unit, TopNode] = component(props)
}

