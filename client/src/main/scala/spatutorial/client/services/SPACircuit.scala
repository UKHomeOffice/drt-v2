package spatutorial.client.services

import autowire._
import diode.ActionResult.ModelUpdate
import diode._
import diode.data._
import diode.util._
import diode.react.ReactConnector
import spatutorial.shared.FlightsApi.Flights
import spatutorial.shared.{Api, CrunchResult, SimulationResult, TodoItem}
import boopickle.Default._

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.Random
import spatutorial.client.logger._
import spatutorial.client.services.HandyStuff.tupleMagic

// Actions
case object RefreshTodos extends Action

case class UpdateAllTodos(todos: Seq[TodoItem]) extends Action

case class UpdateTodo(item: TodoItem) extends Action

case class DeleteTodo(item: TodoItem) extends Action

case class UpdateMotd(potResult: Pot[String] = Empty) extends PotAction[String, UpdateMotd] {
  override def next(value: Pot[String]) = UpdateMotd(value)
}

case class UpdateCrunchResult(crunchResult: CrunchResult) extends Action

case class UpdateSimulationResult(simulationResult: SimulationResult) extends Action

case class UpdateWorkloads(workloads: Seq[Double]) extends Action

case class Crunch(workload: Seq[Double]) extends Action

case class GetWorkloads(begin: String, end: String, port: String) extends Action

case class RunSimulation(workloads: Seq[Double], desks: Seq[Double]) extends Action

case class ChangeDeskUsage(value: String, index: Int) extends Action

case class ProcessWork(desks: Seq[Double], workload: Seq[Double]) extends Action

// The base model of our application
case class Workloads(workloads: Seq[Double] = Nil)

case class RootModel(todos: Pot[Todos],
                     motd: Pot[String],
                     workload: Pot[Workloads],
                     crunchResult: Pot[CrunchResult],
                     realDesks: Pot[Seq[Double]],
                     simulationResult: Pot[SimulationResult],
                     flights: Pot[Flights]
                    )

case class Todos(items: Seq[TodoItem]) {
  def updated(newItem: TodoItem) = {
    items.indexWhere(_.id == newItem.id) match {
      case -1 =>
        // add new
        Todos(items :+ newItem)
      case idx =>
        // replace old
        Todos(items.updated(idx, newItem))
    }
  }

  def remove(item: TodoItem) = Todos(items.filterNot(_ == item))
}

/**
  * Handles actions related to todos
  *
  * @param modelRW Reader/Writer to access the model
  */
class TodoHandler[M](modelRW: ModelRW[M, Pot[Todos]]) extends ActionHandler(modelRW) {
  override def handle = {
    case RefreshTodos =>
      log.info("RefreshTodos")
      effectOnly(Effect(AjaxClient[Api].getAllTodos().call().map(UpdateAllTodos)))
    case UpdateAllTodos(todos) =>
      // got new todos, update model
      updated(Ready(Todos(todos)))
    case UpdateTodo(item) =>
      log.info("UpdateTodo")
      // make a local update and inform server
      updated(value.map(_.updated(item)), Effect(AjaxClient[Api].updateTodo(item).call().map(UpdateAllTodos)))
    case DeleteTodo(item) =>
      // make a local update and inform server
      updated(value.map(_.remove(item)), Effect(AjaxClient[Api].deleteTodo(item.id).call().map(UpdateAllTodos)))
  }
}

/**
  * Handles actions related to the Motd
  *
  * @param modelRW Reader/Writer to access the model
  */
class MotdHandler[M](modelRW: ModelRW[M, Pot[String]]) extends ActionHandler(modelRW) {
  implicit val runner = new RunAfterJS

  override def handle = {
    case action: UpdateMotd =>
      val updateF = action.effect(AjaxClient[Api].welcomeMsg("User X").call())(identity _)
      action.handleWith(this, updateF)(PotAction.handler())
  }
}

class WorkloadHandler[M](modelRW: ModelRW[M, Pot[Workloads]]) extends ActionHandler(modelRW) {
  protected def handle = {
    case action: GetWorkloads =>
      log.info("requesting workloads from server")
      effectOnly(Effect(AjaxClient[Api].getWorkloads().call().map(UpdateWorkloads)))
    case UpdateWorkloads(workloads) =>
      log.info(s"received workloads ${workloads} from server")
      updated(Ready(Workloads(workloads)), Effect(AjaxClient[Api].crunch(workloads).call().map(UpdateCrunchResult)))
  }
}

object HandyStuff {
  type tupleMagic = (Pot[CrunchResult], Pot[SimulationResult])
}

class SimulationHandler[M](
                            modelRW: ModelRW[M, tupleMagic])
  extends ActionHandler(modelRW) {
  protected def handle = {
    case RunSimulation(workloads, desks) =>
      log.info("Requesting ")
      effectOnly(Effect(
        AjaxClient[Api].processWork(workloads, desks.map(_.toInt))
          .call()
          .map(UpdateSimulationResult)))
    case UpdateSimulationResult(simResult) =>
      log.info(s"Got simulation result $simResult")
      noChange
    case ChangeDeskUsage(v, k) =>
      log.info(s"Handler: ChangeDesk($v, $k)")
      val crunchModel: ModelR[M, Pot[CrunchResult]] = modelRW.zoom(_._1)
      val simModel: ModelR[M, Pot[SimulationResult]] = modelRW.zoom(_._2)
      val map: Pot[SimulationResult] = simModel.value.map(cr => {
        val newRecDesks = cr.recommendedDesks.toArray
        for (n <- k until k + 15) {
          newRecDesks(n) = v.toInt
        }
        cr.copy(recommendedDesks = newRecDesks)
      })
      val newValSimulation: Pot[SimulationResult] = map
      val newVal = (crunchModel.value, newValSimulation)
      ModelUpdate(modelRW.updatedWith(modelRW.root.value, newVal))
  }
}

case class RequestFlights(from: Long, to: Long) extends Action

case class UpdateFlights(flights: Flights) extends Action

class FlightsHandler[M](modelRW: ModelRW[M, Pot[Flights]]) extends ActionHandler(modelRW) {
  protected def handle = {
    case RequestFlights(from, to) =>
      //      effectOnly(Effect(AjaxClient[Api].flights(from, to)))
      noChange
  }
}

class CrunchHandler[M](modelRW: ModelRW[M, tupleMagic]) extends ActionHandler(modelRW) {

  override def handle = {
    case Crunch(workload) =>
      log.info(s"Crunch Sending ${workload}")
      effectOnly(Effect(AjaxClient[Api].crunch(workload).call().map(UpdateCrunchResult)))
    case UpdateCrunchResult(crunchResult) =>
      log.info("UpdateCrunchResult")
      //      updated(Ready(r))

      val crunchModel: ModelR[M, Pot[CrunchResult]] = modelRW.zoom(_._1)
      val simModel: ModelR[M, Pot[SimulationResult]] = modelRW.zoom(_._2)
      val map: Pot[SimulationResult] = simModel.value.map(cr => {
        cr.copy(recommendedDesks = crunchResult.recommendedDesks)
      })
      val newValSimulation: Pot[SimulationResult] = map
      val newVal = (Ready(crunchResult), newValSimulation)
      ModelUpdate(modelRW.updatedWith(modelRW.root.value, newVal))
  }

}

// Application circuit
object SPACircuit extends Circuit[RootModel] with ReactConnector[RootModel] {
  val blockWidth = 15
  //  val workloads = Iterator.continually(Random.nextInt(20).toDouble).take(30 * blockWidth).toSeq

  // initial application model
  override protected def initialModel = RootModel(Empty, Empty,
    Empty, //Ready(Workloads(workloads)),
    Empty,
    Empty,
    Empty,
    Empty)

  // combine all handlers into one
  override protected val actionHandler = {
    println("composing handlers")
    composeHandlers(
      new TodoHandler(zoomRW(_.todos)((m, v) => m.copy(todos = v))),
      new MotdHandler(zoomRW(_.motd)((m, v) => m.copy(motd = v))),
      new WorkloadHandler(zoomRW(_.workload)((m, v) => m.copy(workload = v))),
      new CrunchHandler(zoomRW(m => (m.crunchResult, m.simulationResult))((m, v) =>
        m.copy(simulationResult = v._2, crunchResult = v._1))),
      new SimulationHandler(zoomRW(m => (m.crunchResult, m.simulationResult))((m, v) =>
        m.copy(simulationResult = v._2, crunchResult = v._1)))
    )
  }

}
