package spatutorial.client.modules

import scala.scalajs.js.{Object, JSON}
import chandu0101.scalajs.react.components.ReactTable.Backend
import chandu0101.scalajs.react.components.{ReactTable, JsonUtil, Spinner}
import chandu0101.scalajs.react.components.materialui.{MuiPaper, ZDepth, DeterminateIndeterminate, MuiCircularProgress}
import com.payalabs.scalajs.react.bridge.ReactBridgeComponent
import com.sun.org.apache.xpath.internal.operations.Bool
import diode.data.{Ready, Pot}
import diode.react.{ReactPot, ReactConnectProxy, ModelProxy}
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
import spatutorial.client.SPAMain.{Loc, TodoLoc}
import spatutorial.client.components.Bootstrap.Panel
import spatutorial.client.components._
import spatutorial.client.services.{Crunch, GetWorkloads, Workloads}
import spatutorial.shared.FlightsApi.Flights
import com.payalabs.scalajs.react.bridge._

import scala.scalajs.js
import scala.util.Random
import scala.language.existentials
import spatutorial.client.logger._

case class TagsInput(id: js.UndefOr[String] = js.undefined,
                     className: js.UndefOr[String] = js.undefined,
                     ref: js.UndefOr[String] = js.undefined,
                     key: js.UndefOr[Any] = js.undefined,
                     defaultValue: js.UndefOr[Seq[String]] = js.undefined,
                     value: js.UndefOr[Array[String]] = js.undefined,
                     placeholder: js.UndefOr[String] = js.undefined,
                     onChange: js.UndefOr[js.Array[String] => Unit] = js.undefined,
                     validate: js.UndefOr[String => Boolean] = js.undefined,
                     transform: js.UndefOr[String => String] = js.undefined)
  extends ReactBridgeComponent

//<Griddle results={fakeData} tableClassName="table" showFilter={true}
//showSettings={true} columns={["name", "city", "state", "country"]}/>
case class GriddleComponentWrapper(results: js.Any, //Seq[Map[String, Any]],
                                   columns: Seq[String],
                                   showSettings: Boolean = true,
                                   showFilter: Boolean = true
                                  ) {
  def toJS = {
    val p = js.Dynamic.literal()
    p.updateDynamic("results")(results)
    p.updateDynamic("columns")(columns)
    p.updateDynamic("showSettings")(showSettings)
    p.updateDynamic("showFilter")(showFilter)
    p
  }

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

  import com.payalabs.scalajs.react.bridge.ReactBridgeComponent
  import chandu0101.scalajs.react.components.Implicits._
  import org.scalajs.dom

  //  import com.payalabs.scalajs.react.bridge.elements.{ReactMediumEditor, Input, Button, TagsInput}
  import japgolly.scalajs.react.vdom.all.{onChange => _, _}
  import japgolly.scalajs.react._
  import org.scalajs.dom

  import scala.scalajs.js

  import scala.language.existentials

  case class Props(router: RouterCtl[Loc],
                   flightsModelProxy: ModelProxy[Pot[Flights]],
                   airportInfoProxy: ModelProxy[Map[String, Pot[AirportInfo]]])

  case class State(flights: ReactConnectProxy[Pot[Flights]],
                   airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]])

  val component = ReactComponentB[Props]("Flights")
    .initialState_P(p => {
      log.info("initialising flights component")
      State(
        p.flightsModelProxy.connect(m => m),
        p.airportInfoProxy.connect(m => m)
      )
    }
    ).renderPS((_, props, state) => {
    log.info("rendering flights")
    Panel(Panel.Props("Flights"),
      <.h2("Flights"),
      state.airportInfo(ai =>
        state.flights(x => {
          log.info("rendering flight rows")
          <.div(^.className := "table-responsive",
            props.flightsModelProxy.value.renderPending((t) => Spinner()()),
            //            props.flightsModelProxy.value.renderEmpty(TableTest.component()),
            //              MuiPaper(zDepth = ZDepth._1, rounded = false)(<.p("rounded = false"))), //MuiCircularProgress(mode = DeterminateIndeterminate.indeterminate, size = 0.5)()),
            props.flightsModelProxy.value.renderReady(flights => {
              div(
                GriddleComponentWrapper(results = reactTableFlightsAsJsonDynamic(flights).toJsArray, columns = columnNames)(),
                GriddleComponentWrapper(results = TableTest.fakeData, columns = Seq("fname", "lname", "email", "country"))())
              //              reactTable(flights)
            })
            //                TableTest.component()
            //              <.table(
            //                ^.className := "table", ^.className := "table-striped",
            //                <.tbody(flightHeaders,
            //                  flights.flights.sortBy(_.Operator).reverse.map(flightRow(_, ai.value))) )
          )
        })))
  }).componentDidMount((scope) => Callback.when(scope.props.flightsModelProxy.value.isEmpty) {
    log.info("Flights View is empty, requesting flights")
    scope.props.flightsModelProxy.dispatch(RequestFlights(0, 0))
  }).build

  def reactTable(flights: Flights) = {
    val data = reactTableFlights(flights).toVector
    val config = List(
      ("SchDT", None, Some(ReactTable.getStringSort("SchDT")), None),
      ("Origin", None, Some(ReactTable.getStringSort("Origin")), None))
    ReactTable(data = data, columns = columnNames, rowsPerPage = 50, config = config)
  }

  def reactTableFlights(flights: Flights): List[Map[String, Any]] = {
    flights.flights.map(f => {
      Map(
        "Operator" -> f.Operator,
        "Status" -> f.Status,
        "EstDT" -> f.EstDT,
        "ActDT" -> f.ActDT,
        "EstChoxDT" -> f.EstChoxDT,
        "ActChoxDT" -> f.ActChoxDT,
        "Gate" -> f.Gate,
        "Stand" -> f.Stand,
        "MaxPax" -> f.MaxPax,
        "ActPax" -> f.ActPax,
        "TranPax" -> f.TranPax,
        "RunwayID" -> f.RunwayID,
        "BaggageReclaimId" -> f.BaggageReclaimId,
        "FlightID" -> f.FlightID,
        "AirportID" -> f.AirportID,
        "Terminal" -> f.Terminal,
        "ICAO" -> f.ICAO,
        "IATA" -> f.IATA,
        "Origin" -> f.Origin,
        "SchDT" -> f.SchDT)
    })
  }

  def reactTableFlightsAsJsonDynamic(flights: Flights): List[js.Dynamic] = {
    flights.flights.map(f => {
      js.Dynamic.literal(
        Operator = f.Operator,
        Status = f.Status,
        EstDT = f.EstDT,
        ActDT = f.ActDT,
        EstChoxDT = f.EstChoxDT,
        ActChoxDT = f.ActChoxDT,
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
        SchDT = f.SchDT)
    })
  }

  def flightHeaders() = {
    val hs = columnNames
    <.tr(hs.map(<.th(_)))
  }

  def columnNames: List[String] = {
    List(
      "SchDT",
      "Origin",
      "Operator",
      "Status",
      "EstDT",
      "ActDT",
      "EstChoxDT",
      "ActChoxDT",
      "Gate",
      "Stand",
      "MaxPax",
      "ActPax",
      "TranPax",
      "RunwayID",
      "BaggageReclaimId",
      "FlightID",
      "AirportID",
      "Terminal",
      "ICAO",
      "IATA"
    )
  }

  def flightRow(f: ApiFlight, ai: Map[String, Pot[AirportInfo]]) = {
    val maybePot = ai.get(f.Origin)
    val tt = maybePot.map { v =>
      v.map(t => ^.title := s"${t.airportName}, ${t.city}, ${t.country}")
    }
    val extracted = tt match {
      case Some(Ready(tag)) => tag
      case _ => ^.title := "pending country"
    }
    val vals = List(
      <.td(f.Operator),
      <.td(f.Status),
      <.td(f.EstDT),
      <.td(f.ActDT),
      <.td(f.EstChoxDT),
      <.td(f.ActChoxDT),
      <.td(f.Gate),
      <.td(f.Stand),
      <.td(f.MaxPax.toString),
      <.td(f.ActPax.toString),
      <.td(f.TranPax.toString),
      <.td(f.RunwayID),
      <.td(f.BaggageReclaimId),
      <.td(f.FlightID.toString),
      <.td(f.AirportID),
      <.td(f.Terminal),
      <.td(f.ICAO),
      <.td(f.IATA),
      <.td(f.Origin, ReactAttr("data-toggle") := "popover", ReactAttr("data-content") := "content!", extracted),
      <.td(f.SchDT))

    <.tr(vals)
  }

  def apply(props: Props, proxy: ModelProxy[Pot[Flights]]) = component(props)
}
