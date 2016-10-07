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

/**
  * Created by rich on 30/09/16.
  */
object UserDeskRecsComponent {

  case class Props(
                   terminalName: TerminalName,
                   queueName: QueueName,
                   items: Seq[UserDeskRecsRow],
                   flights: Pot[Flights],
                   airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                   proxy: ModelProxy[Pot[UserDeskRecs]],
                   simulationResult: ModelProxy[Pot[SimulationResult]])

  case class State(selectedItem: Option[DeskRecTimeslot] = None, showTodoForm: Boolean = false)

  class Backend($: BackendScope[Props, State]) {
    def mounted(props: Props): Callback = {
      log.info("*****************UserDeskRecsComponent mounted")
      // dispatch a message to refresh the todos, which will cause TodoStore to fetch todos from the server
      Callback.when(false) {
        props.proxy.dispatch(GetWorkloads("", "", "edi"))
      }
      Callback.when(props.proxy().isEmpty)(props.proxy.dispatch(RefreshTodos))

      Callback.when(props.proxy().isReady)(
        props.proxy.dispatch(RunSimulation(props.terminalName, props.queueName, Nil, props.proxy().get.items.map(_.deskRec).toList))
      )
    }

    def editTodo(item: Option[DeskRecTimeslot]) =
    // activate the edit dialog
      $.modState(s => s.copy(selectedItem = item, showTodoForm = true))

    def render(p: Props, s: State) =
      Panel(Panel.Props(s"Enter your real (or projected) desk numbers to see projected queue times for queue '${p.queueName}'"), <.div(
        p.proxy().renderFailed(ex => "Error loading"),
        p.proxy().renderPending(_ > 10, _ => "Loading..."),
        p.simulationResult().renderReady(sr =>
          p.proxy().render(userDeskRecs => {
            log.info(s"rendering ${p.queueName} with ${userDeskRecs.items.length}")
            <.div(^.cls := "user-desk-recs-container table-responsive",
              TableTodoList(
                p.items,
                p.flights,
                p.airportInfos,
                sr,
                item => p.proxy.dispatch(UpdateDeskRecsTime(p.terminalName, p.queueName, item)),
                item => editTodo(Some(item)),
                item => p.proxy.dispatch(DeleteTodo(item))))
          }))))
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
            flights: ModelProxy[Pot[Flights]],
            proxy: ModelProxy[Pot[UserDeskRecs]],
            simulationResult: ModelProxy[Pot[SimulationResult]]) =
    component(Props(terminalName, queueName, items, flights.value, airportInfo, proxy, simulationResult))
}
