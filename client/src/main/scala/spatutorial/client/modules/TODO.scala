package spatutorial.client.modules

import diode.react.ReactPot._
import diode.react._
import diode.data.Pot
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.components.Bootstrap._
import spatutorial.client.components.TableTodoList.UserDeskRecsRow
import spatutorial.client.components._
import spatutorial.client.logger._
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.{Flights, QueueName}
import spatutorial.shared._

import scalacss.ScalaCssReact._

object UserDeskRecsComponent {

  case class Props(queueName: QueueName,
                   items: Seq[UserDeskRecsRow],
                   flights: Pot[Flights],
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
            <.div(^.cls := "user-desk-recs-container",
              TableTodoList(
                p.items,
                p.flights,
                sr,
                item => p.proxy.dispatch(UpdateDeskRecsTime(p.queueName, item)),
                item => editTodo(Some(item)),
                item => p.proxy.dispatch(DeleteTodo(item))))
          })),
        p.proxy().render(todos =>
          Button(
            Button.Props(
              p.proxy.dispatch(RunSimulation(p.queueName, Nil, todos.items.map(_.deskRec).toList))),
            Icon.play,
            "Run Simulation"
          ))))
  }

  // create the React component for To Do management
  val component = ReactComponentB[Props]("TODO")
    .initialState(State()) // initial state from TodoStore
    .renderBackend[Backend]
    .componentDidMount(scope => scope.backend.mounted(scope.props))
    .build

  /** Returns a function compatible with router location system while using our own props */
  def apply(queueName: QueueName,
            items: Seq[UserDeskRecsRow],
            flights: ModelProxy[Pot[Flights]],
            proxy: ModelProxy[Pot[UserDeskRecs]],
            simulationResult: ModelProxy[Pot[SimulationResult]]) = component(Props(queueName, items, flights.value, proxy, simulationResult))
}

