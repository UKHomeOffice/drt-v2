package spatutorial.client.modules

import diode.data.Pot
import diode.react.{ReactConnectProxy, ModelProxy}
import japgolly.scalajs.react.ReactComponentB
import japgolly.scalajs.react.extra.router.RouterCtl
import spatutorial.client.SPAMain.Loc
import spatutorial.client.components.Bootstrap.Panel
import spatutorial.client.services.RequestFlights
import spatutorial.shared.FlightsApi.Flights
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.logger._

object FlightsView {

  import scala.language.existentials

  case class Props(router: RouterCtl[Loc], flightsModelProxy: ModelProxy[Pot[Flights]])

  case class State(flights: ReactConnectProxy[Pot[Flights]])

  val component = ReactComponentB[Props]("Flights")
    .initialState_P(p =>
      State(p.flightsModelProxy.connect(m => m))
    ).renderPS((_, proxy, state) => Panel(Panel.Props("Flights"),
    <.h2("Flights"),
    <.table(^.className := "table", ^.className := "table-striped",
      <.thead(<.th("Scheduled Arrival Date"), <.th("Flight Number"), <.th("Carrier"), <.th("Actual Arrival Date"), <.th("Status"), <.th("Pax")),
      <.tbody(
        <.tr(<.td("2016-05-10T13:39"), <.td("123"), <.td("BA"), <.td("3023-never"), <.td("TimeTraveller"), <.td("Inf")),
        <.tr(<.td("2016-05-10T13:39"), <.td("123"), <.td("BA"), <.td("3023-never"), <.td("TimeTraveller"), <.td("123")),
        <.tr(<.td("2016-05-10T13:39"), <.td("123"), <.td("BA"), <.td("3023-never"), <.td("Engines Failing"), <.td("50")),
        <.tr(<.td("2016-05-10T13:39"), <.td("123"), <.td("BA"), <.td("3023-never"), <.td("What do you think?"), <.td("19")),
        <.tr(<.td("2016-05-10T13:39"), <.td("123"), <.td("BA"), <.td("3023-never"), <.td("BaggageUnloaded"), <.td("32")),
        <.tr(<.td("2016-05-10T13:39"), <.td("123"), <.td("BA"), <.td("3023-never"), <.td("OnChox"), <.td("Inf")))
    )
  )).componentDidMount((scope) => Callback.when(scope.props.flightsModelProxy.value.isEmpty) {
    log.info("Flights View is empty, requesting flights")
    scope.props.flightsModelProxy.dispatch(RequestFlights(0, 0))
  }).build

  def apply(props: Props, proxy: ModelProxy[Pot[Flights]]) = component(props)
}
