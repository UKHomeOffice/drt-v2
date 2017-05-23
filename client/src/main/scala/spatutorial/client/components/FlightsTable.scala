package drt.client.components

//import chandu0101.scalajs.react.components.Spinner
import diode.data.{Pot, Ready}
//import japgolly.scalajs.react.vdom.all.{VdomAttr => _, TagMod => _, _react_attrString => _, _react_autoRender => _, _react_fragReactNode => _}
import japgolly.scalajs.react.vdom.html_<^._
import drt.client.modules.{FlightsView, GriddleComponentWrapper, ViewTools}
import drt.shared.AirportInfo
import drt.shared.FlightsApi.{Flights}
import japgolly.scalajs.react._


import scala.language.existentials
import scala.scalajs.js

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

  val component = ScalaComponent.builder[FlightsView.Props]("FlightsTable")
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
      <.div(^.className := "table-responsive timeslot-flight-popover",
        props.flightsModelProxy.renderPending((t) => ViewTools.spinner),
        props.flightsModelProxy.renderEmpty(ViewTools.spinner),
        props.flightsModelProxy.renderReady(flights => {
            FlightsWithSplitsTable.ArrivalsTable()(flights)
          //          GriddleComponentWrapper[Dynamic](results = reactTableFlightsAsJsonDynamic(flights),
//            columnMeta = columnMeta,
//            initialSort = "SchDt",
//            columns = props.activeCols)()
        })
      )
    }).build

  def apply(props: FlightsView.Props) = component(props)
}



