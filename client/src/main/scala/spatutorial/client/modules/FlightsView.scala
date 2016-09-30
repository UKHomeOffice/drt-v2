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

case class GriddleComponentWrapper(results: js.Any, //Seq[Map[String, Any]],
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

//case class Griddle(id: js.UndefOr[String] = js.undefined,
//                   className: js.UndefOr[String] = js.undefined,
//                   ref: js.UndefOr[String] = js.undefined,
//                   key: js.UndefOr[Any] = js.undefined,
//                   results: js.UndefOr[Any] = js.undefined,
//                   tableClassName: js.UndefOr[String] = "table",
//                   showFilter: js.UndefOr[Boolean] = true,
//                   showSettings: js.UndefOr[Boolean] = true,
//                   columns: js.UndefOr[Seq[String]] = js.undefined
//                  ) extends ReactBridgeComponent

//object CodeExample {
//
//  object Style {
//
//    val pageBodyContent = Seq(^.borderRadius := "2px",
//      ^.boxShadow := "0 1px 4px rgba(223, 228, 228, 0.79)",
//      ^.maxWidth := "1024px")
//
//    val contentDemo = Seq(^.padding := "30px")
//
//    val contentCode = Seq(^.borderTop := "solid 1px #e0e0e0"
//    )
//
//    val title = Seq(
//      ^.paddingBottom := "15px")
//
//  }
//
//  case class Backend($: BackendScope[Props, _]) {
//    def render(P: Props, C: PropsChildren) = {
//      <.div(
//        P.title.nonEmpty ?= <.h3(P.title, Style.title),
//        <.div(Style.pageBodyContent)(
//          <.div(Style.contentDemo, ^.key := "dan")(
//            C
//          ),
//          <.pre(Style.contentCode, ^.key := "code")(P.code)
//        )
//      )
//    }
//  }
//
//  val component = ReactComponentB[Props]("codeexample")
//    .renderBackend[Backend]
//    .build
//
//  case class Props(code: String, title: String)
//
//  def apply(code: String,
//            title: String,
//            ref: js.UndefOr[String] = "",
//            key: js.Any = {})
//           (children: ReactNode*) =
//    component.set(key, ref)(Props(code, title), children: _*)
//}

object TableTest {

  object SampleData {

    val personJson =
      """
        |[ {"fname": "Joshua", "lname": "Myers", "email": "jmyers0@trellian.com", "country": "France"},
        | {"fname": "Gloria", "lname": "Porter", "email": "gporter1@hatena.ne.jp", "country": "Indonesia"},
        | {"fname": "Joe", "lname": "Elliott", "email": "jelliott2@mediafire.com", "country": "Brazil"},
        | {"fname": "Larry", "lname": "Henry", "email": "lhenry3@goo.ne.jp", "country": "Philippines"},
        | {"fname": "Frances", "lname": "Roberts", "email": "froberts4@fema.gov", "country": "Mexico"},
        | {"fname": "Ashley", "lname": "Turner", "email": "aturner5@paypal.com", "country": "Brazil"},
        | {"fname": "Jeremy", "lname": "Morris", "email": "jmorris6@yale.edu", "country": "China"},
        | {"fname": "Todd", "lname": "Carter", "email": "tcarter7@printfriendly.com", "country": "Peru"},
        | {"fname": "Antonio", "lname": "Hart", "email": "ahart8@webs.com", "country": "Brazil"},
        | {"fname": "Henry", "lname": "Welch", "email": "hwelch9@soup.io", "country": "Paraguay"}
        ]""".stripMargin('|')
  }

  val data: Vector[Map[String, Any]] =
    JsonUtil.jsonArrayToMap(SampleData.personJson)


  val fakeData: js.Dynamic = JSON.parse(SampleData.personJson)

  val columns: List[String] =
    List("fname", "lname", "email", "country")

  case class Backend($: BackendScope[_, _]) {
    def render =
      <.div(
        <.h2(^.cls := "mui-font-style-headline")("Basic Table"),
        //        CodeExample(code, "ReactTableBasic")(
        //        <.p("hello")
        ReactTable(data = data, columns = columns, rowsPerPage = 10)
        //        )
      )
  }

  val component = ReactComponentB[Unit]("plain")
    .renderBackend[Backend]
    .build
}

object FlightsView {

  import japgolly.scalajs.react.vdom.all.{onChange => _, _}
  import japgolly.scalajs.react._
  import scala.scalajs.js
  import scala.language.existentials

  case class Props(flightsModelProxy: Pot[Flights],
                   airportInfoProxy: Map[String, Pot[AirportInfo]])

  case class State(flights: ReactConnectProxy[Pot[Flights]],
                   airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]])

  val component = ReactComponentB[Props]("Flights")
    .render_P((props) => {
      Panel(Panel.Props("Flights"),
        <.h2("Flights"), FlightsTable(props))
    }).build

  def apply(props: Props): ReactComponentU[Props, Unit, Unit, TopNode] = component(props)
}

object FlightsTable {
  def originComponent(originMapper: (String) => (String)): js.Function = (props: js.Dynamic) => {
    val mod: TagMod = ^.title := originMapper(props.data.toString())
    <.span(props.data.toString(), mod).render
  }

  def reactTableFlightsAsJsonDynamic(flights: Flights): List[js.Dynamic] = {
    flights.flights.map(f => {
      js.Dynamic.literal(
        Operator = f.Operator,
        Status = f.Status,
        EstDT = makeDTReadable(f.EstDT),
        ActDT = makeDTReadable(f.ActDT),
        EstChoxDT = f.EstChoxDT,
        ActChoxDT = makeDTReadable(f.ActChoxDT),
        Gate = f.Gate,
        Stand = f.Stand,
        MaxPax = f.MaxPax,
        ActPax = f.ActPax,
        TranPax = f.TranPax,
        RunwayID = f.RunwayID,
        BaggageReclaimId = f.BaggageReclaimId,
        FlightID = f.FlightID,
        AirportID = f.AirportID,
        Terminal = f.Terminal,
        ICAO = f.ICAO,
        IATA = f.IATA,
        Origin = f.Origin,
        SchDT = makeDTReadable(f.SchDT))
    })
  }

  def makeDTReadable(dt: String) = dt.replace("T", " ").take(16)

  def columnNames: List[String] = {
    List(
      "SchDT",
      "Origin",
      "Operator",
      "Status",
      "EstDT",
      "ActDT",
      //      "EstChoxDT",
      "ActChoxDT",
      "Gate",
      "Stand",
      "MaxPax",
      "ActPax",
      //      "TranPax",
      //      "RunwayID",
      //      "BaggageReclaimId",
      //      "FlightID",
      //      "AirportID",
      "Terminal",
      //      "ICAO",
      "IATA"
    )
  }

  val component = ReactComponentB[FlightsView.Props]("FlightsTable")
    .render_P(props => {
      val portMapper: Map[String, Pot[AirportInfo]] = props.airportInfoProxy

      def mappings(port: String) = {
        val res: Option[Pot[String]] = portMapper.get(port).map { info =>
          info.map(i => s"${i.airportName}, ${i.city}, ${i.country}")
        }
        res match {
          case Some(Ready(v)) => v
          case _ => "waiting for info..."
        }
      }

      val columnMeta = Some(Seq(new GriddleComponentWrapper.ColumnMeta("Origin", customComponent = originComponent(mappings))))
      <.div(^.className := "table-responsive",
        props.flightsModelProxy.renderPending((t) => Spinner()()),
        props.flightsModelProxy.renderEmpty(Spinner()()),
        props.flightsModelProxy.renderReady(flights => {
          GriddleComponentWrapper(results = reactTableFlightsAsJsonDynamic(flights).toJsArray,
            columnMeta = columnMeta,
            columns = columnNames)()
        })
      )
    }).build

  def apply(props: FlightsView.Props) = component(props)
}