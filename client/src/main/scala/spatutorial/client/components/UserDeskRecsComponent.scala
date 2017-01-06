package spatutorial.client.components

import diode.data.Pot
import diode.react.{ModelProxy, ReactConnectProxy}
import japgolly.scalajs.react.{BackendScope, Callback, _}
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.components.Bootstrap.{Button, Panel}
import spatutorial.client.components.DeskRecsTable.UserDeskRecsRow
import spatutorial.client.logger._
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.{Flights, _}
import spatutorial.shared.{HasAirportConfig, AirportConfig, AirportInfo, SimulationResult}

object UserDeskRecsComponent {

  case class Props(
                    terminalName: TerminalName,
                    queueName: QueueName,
                    items: Seq[UserDeskRecsRow],
                    flightsPotRCP: ReactConnectProxy[Pot[Flights]],
                    airportConfig: AirportConfig,
                    airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                    userDeskRecsPotProxy: ModelProxy[Pot[DeskRecTimeSlots]],
                    simulationResult: ModelProxy[Pot[SimulationResult]])

  case class State(selectedItem: Option[DeskRecTimeslot] = None, showTodoForm: Boolean = false)

  class Backend($: BackendScope[Props, State]) {
    def mounted(props: Props): Callback = {
      log.info("*****************UserDeskRecsComponent mounted")
      // dispatch a message to refresh the todos, which will cause TodoStore to fetch todos from the server
      Callback.when(false) {
        props.userDeskRecsPotProxy.dispatch(GetWorkloads("", ""))
      }
//      Callback.when(props.userDeskRecsPotProxy().isEmpty)(props.userDeskRecsPotProxy.dispatch(RefreshTodos))
    }

    def editTodo(item: Option[DeskRecTimeslot]) =
    // activate the edit dialog
      $.modState(s => s.copy(selectedItem = item, showTodoForm = true))

    def render(p: Props, s: State) =
      <.div(^.cls := "user-desk-recs-container",
        p.userDeskRecsPotProxy().renderFailed(ex => "Error loading"),
        p.userDeskRecsPotProxy().renderPending(_ > 10, _ => "Loading..."),
        p.userDeskRecsPotProxy().render(userDeskRecs => {
          log.info(s"rendering ${getClass()} ${p.terminalName}, ${p.queueName} with ${userDeskRecs.items.length}")
          <.div(^.cls := "table-responsive",
            DeskRecsTable(
              p.queueName,
              p.terminalName,
              p.items,
              p.flightsPotRCP,
              p.airportConfig,
              p.airportInfos,
              deskRecTimeslot => p.userDeskRecsPotProxy.dispatch(UpdateDeskRecsTime(p.terminalName, p.queueName, deskRecTimeslot))))
        }))
  }

  // create the React component for To Do management
  val component = ReactComponentB[Props]("TODO")
    .initialState(State()) // initial state from TodoStore
    .renderBackend[Backend]
    .componentDidMount(scope => scope.backend.mounted(scope.props))
    .build

  /** Returns a function compatible with router location system while using our own props */
  def apply(
             terminalName: TerminalName,
             queueName: QueueName,
             items: Seq[UserDeskRecsRow],
             airportConfig: AirportConfig,
             airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
             flightsPotRCP: ReactConnectProxy[Pot[Flights]],
             proxy: ModelProxy[Pot[DeskRecTimeSlots]],
             simulationResult: ModelProxy[Pot[SimulationResult]]) =
  component(Props(terminalName, queueName, items, flightsPotRCP, airportConfig, airportInfo, proxy, simulationResult))
}
