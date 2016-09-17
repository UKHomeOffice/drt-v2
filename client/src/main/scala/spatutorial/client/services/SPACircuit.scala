package spatutorial.client.services

import autowire._
import diode.ActionResult.ModelUpdate
import diode._
import diode.data._
import diode.util._
import diode.react.ReactConnector
import spatutorial.client.components.DeskRecsChart
import spatutorial.client.services.HandyStuff.tupleMagic
import spatutorial.shared.FlightsApi.Flights
import spatutorial.shared._
import boopickle.Default._
import scala.collection.immutable.IndexedSeq
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.Random
import spatutorial.client.logger._

// Actions
case object RefreshTodos extends Action

case class UpdateAllTodos(todos: Seq[DeskRecTimeslot]) extends Action

case class UpdateDeskRecsTime(item: DeskRecTimeslot) extends Action

case class DeleteTodo(item: DeskRecTimeslot) extends Action

case class UpdateMotd(potResult: Pot[String] = Empty) extends PotAction[String, UpdateMotd] {
  override def next(value: Pot[String]) = UpdateMotd(value)
}

case class UpdateCrunchResult(crunchResult: CrunchResult) extends Action

case class UpdateSimulationResult(simulationResult: SimulationResult) extends Action

case class UpdateWorkloads(workloads: List[Double]) extends Action

case class Crunch(workload: List[Double]) extends Action

case class GetWorkloads(begin: String, end: String, port: String) extends Action

case class RunSimulation(workloads: List[Double], desks: List[Int]) extends Action

case class ChangeDeskUsage(value: String, index: Int) extends Action

case class ProcessWork(desks: Seq[Double], workload: Seq[Double]) extends Action

// The base model of our application
case class Workloads(workloads: List[Double] = Nil)

case class RootModel(todos: Pot[UserDeskRecs],
                     motd: Pot[String],
                     workload: Pot[Workloads],
                     crunchResult: Pot[CrunchResult],
                     realDesks: Pot[Seq[Double]],
                     userDeskRec: Pot[UserDeskRecs],
                     simulationResult: Pot[SimulationResult],
                     flights: Pot[Flights]
                    )

case class UserDeskRecs(items: Seq[DeskRecTimeslot]) {
  def updated(newItem: DeskRecTimeslot) = {
    log.info(s"will update ${newItem} into ${items.take(5)}...")
    items.indexWhere(_.id == newItem.id) match {
      case -1 =>
        // add new
        log.info("add new")
        UserDeskRecs(items :+ newItem)
      case idx =>
        log.info("add old")
        // replace old
        UserDeskRecs(items.updated(idx, newItem))
    }
  }
}

/**
  * Handles actions related to todos
  *
  * @param modelRW Reader/Writer to access the model
  */
class TodoHandler[M](modelRW: ModelRW[M, Pot[UserDeskRecs]]) extends ActionHandler(modelRW) {
  override def handle = {
    case RefreshTodos =>
      log.info("RefreshTodos")
//      effectOnly(Effect(AjaxClient[Api].getAllTodos().call().map(UpdateAllTodos)))
      noChange
    case UpdateAllTodos(todos) =>
      // got new todos, update model
      log.info("got new Todos update model")
      updated(Ready(UserDeskRecs(todos)))
    case UpdateDeskRecsTime(item) =>
      log.info(s"Update Desk Recs time ${item} into ${value}")
      // make a local update and inform server
      updated(value.map(_.updated(item))//, Effect(AjaxClient[Api].updateDeskRecsTime(item).call().map(UpdateAllTodos)))
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
      log.info("requesting workloadsWrapper from server")
      effectOnly(Effect(AjaxClient[Api].getWorkloads().call().map(UpdateWorkloads)))
    case UpdateWorkloads(workloads) =>
      log.info(s"received workloads ${workloads} from server")
      updated(Ready(Workloads(workloads)), Effect(AjaxClient[Api].crunch(workloads).call().map(UpdateCrunchResult)))
  }
}

object HandyStuff {
  type tupleMagic = (Pot[CrunchResult], Pot[UserDeskRecs])
}

class SimulationHandler[M](modelR: ModelR[M, Pot[Workloads]], modelRW: ModelRW[M, Pot[UserDeskRecs]])
  extends ActionHandler(modelRW) {
  protected def handle = {
    case RunSimulation(workloads, desks) =>
      log.info("Requesting simulation")
      log.info("Getting workloads from model")
      val workloads1: List[Double] = modelR.value.get.workloads
      log.info("Got workloads from model")
      effectOnly(
        Effect(AjaxClient[Api].processWork(workloads1, desks.map(_.toInt))
          .call()
          .map(UpdateSimulationResult)))
    case ChangeDeskUsage(v, k) =>
      log.info(s"Handler: ChangeDesk($v, $k)")
      val simModel: ModelRW[M, Pot[UserDeskRecs]] = modelRW
      val newUserRecs: UserDeskRecs = simModel.value.get.updated(DeskRecTimeslot(k.toString, k, v.toInt))
      updated(Ready(newUserRecs))
  }
}

class SimulationResultHandler[M](modelRW: ModelRW[M, Pot[SimulationResult]]) extends ActionHandler(modelRW) {
  protected def handle = {
    case UpdateSimulationResult(simResult) =>
      log.info(s"Got simulation result ${simResult.waitTimes}")
      updated(Ready(simResult))
  }
}

case class RequestFlights(from: Long, to: Long) extends Action

case class UpdateFlights(flights: Flights) extends Action

class FlightsHandler[M](modelRW: ModelRW[M, Pot[Flights]]) extends ActionHandler(modelRW) {
  protected def handle = {
    case RequestFlights(from, to) =>
      effectOnly(Effect(AjaxClient[Api].flights(from, to).call().map(UpdateFlights)))
    case UpdateFlights(flights) =>
      log.info(s"Client got flights! ${flights.flights.length}")
      updated(Ready(flights))
  }
}

class CrunchHandler[M](modelRW: ModelRW[M, tupleMagic]) extends ActionHandler(modelRW) {

  override def handle = {
    case Crunch(workload) =>
      log.info(s"Crunch Sending ${workload}")
      effectOnly(Effect(AjaxClient[Api].crunch(workload).call().map(UpdateCrunchResult)))
    case UpdateCrunchResult(crunchResult) =>
      log.info("UpdateCrunchResult")

      val newDeskRec: UserDeskRecs = UserDeskRecs(DeskRecsChart
        .takeEvery15th(crunchResult.recommendedDesks)
        .zipWithIndex.map(t => DeskRecTimeslot(t._2.toString, t._2, t._1)))

      val newVal = (Ready(crunchResult), Ready(newDeskRec))
      updated(newVal)
  }

}

// Application circuit
object SPACircuit extends Circuit[RootModel] with ReactConnector[RootModel] {
  val blockWidth = 15
  //  val workloadsWrapper = Iterator.continually(Random.nextInt(20).toDouble).take(30 * blockWidth).toSeq

  // initial application model
  override protected def initialModel = RootModel(Empty, Empty,
    Empty, //Ready(Workloads(workloadsWrapper)),
    Empty,
    Empty,
    Empty,
    Empty,
    Empty)

  // combine all handlers into one
  override protected val actionHandler = {
    println("composing handlers")
    composeHandlers(
      new TodoHandler(zoomRW(_.userDeskRec)((m, v) => m.copy(userDeskRec = v))),
      new MotdHandler(zoomRW(_.motd)((m, v) => m.copy(motd = v))),
      new WorkloadHandler(zoomRW(_.workload)((m, v) => m.copy(workload = v))),
      new CrunchHandler(zoomRW(m => (m.crunchResult,  m.userDeskRec))((m, v) => {
        log.info(s"setting crunch result and userdesk recs desks in model ${v._2.take(10)}")
        m.copy(crunchResult = v._1, userDeskRec = v._2)
      })),
      new SimulationHandler(zoom(_.workload), zoomRW(m => m.userDeskRec)((m, v) => {
        log.info("setting simulation result in model")
        m.copy(userDeskRec = v) //, crunchResult = v._1)
      })),
      new SimulationResultHandler(zoomRW(_.simulationResult)((m, v) => m.copy(simulationResult = v))),
      new FlightsHandler(zoomRW(_.flights)((m, v) => m.copy(flights = v))))
  }

}