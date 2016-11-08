package spatutorial.client.components

import diode.data.Pot
import diode.react.{ReactConnectProxy, ModelProxy}
import japgolly.scalajs.react.{BackendScope, Callback, _}
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.components.Bootstrap.{Button, Panel}
import spatutorial.client.components.TableTodoList.UserDeskRecsRow
import spatutorial.client.logger._
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.{Flights, _}
import spatutorial.shared.{AirportInfo, SimulationResult}

object UserDeskRecsComponent {

  case class Props(
                    terminalName: TerminalName,
                    queueName: QueueName,
                    items: Seq[UserDeskRecsRow],
                    flightsPotRCP: ReactConnectProxy[Pot[Flights]],
                    airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                    userDeskRecsPotProxy: ModelProxy[Pot[UserDeskRecs]],
                    simulationResult: ModelProxy[Pot[SimulationResult]])

  case class State(selectedItem: Option[DeskRecTimeslot] = None, showTodoForm: Boolean = false)

  class Backend($: BackendScope[Props, State]) {
    def mounted(props: Props): Callback = {
      log.info("*****************UserDeskRecsComponent mounted")
      // dispatch a message to refresh the todos, which will cause TodoStore to fetch todos from the server
      Callback.when(false) {
        props.userDeskRecsPotProxy.dispatch(GetWorkloads("", "", "edi"))
      }
      Callback.when(props.userDeskRecsPotProxy().isEmpty)(props.userDeskRecsPotProxy.dispatch(RefreshTodos))
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
                TableTodoList(
                  p.queueName,
                  p.terminalName,
                  p.items,
                  p.flightsPotRCP,
                  p.airportInfos,
                  item => p.userDeskRecsPotProxy.dispatch(UpdateDeskRecsTime(p.terminalName, p.queueName, item)),
                  item => editTodo(Some(item)),
                  item => p.userDeskRecsPotProxy.dispatch(DeleteTodo(item))))
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
             airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
             flightsPotRCP: ReactConnectProxy[Pot[Flights]],
             proxy: ModelProxy[Pot[UserDeskRecs]],
             simulationResult: ModelProxy[Pot[SimulationResult]]) =
    component(Props(terminalName, queueName, items, flightsPotRCP, airportInfo, proxy, simulationResult))
}
