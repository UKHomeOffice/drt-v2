package spatutorial.client.services


import java.util.UUID

import autowire._
import diode.Implicits.runAfterImpl
import diode._
import diode.data._
import diode.react.ReactConnector
import spatutorial.client.TableViewUtils
import spatutorial.client.components.TableTerminalDeskRecs.TerminalUserDeskRecsRow
import spatutorial.client.components.{DeskRecsChart, TableTerminalDeskRecs, TerminalDeploymentsTable, TerminalUserDeskRecs}
import spatutorial.client.logger._
import spatutorial.client.services.HandyStuff._
import spatutorial.client.services.RootModel.mergeTerminalQueues
import spatutorial.shared.FlightsApi.{TerminalName, _}
import spatutorial.shared._
import boopickle.Default._
import spatutorial.client.components.TerminalDeploymentsTable.TerminalDeploymentsRow
import spatutorial.client.modules.Dashboard.QueueCrunchResults

import scala.collection.immutable.{Iterable, Map, NumericRange, Seq}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.Date
import scala.util.{Failure, Success, Try}

case class DeskRecTimeslot(timeInMillis: Long, deskRec: Int)

case class UpdateDeskRecsTime(terminalName: TerminalName, queueName: QueueName, item: DeskRecTimeslot) extends Action

case class UpdateCrunchResult(terminalName: TerminalName, queueName: QueueName, crunchResultWithTimeAndInterval: CrunchResult) extends Action

case class UpdateSimulationResult(terminalName: TerminalName, queueName: QueueName, simulationResult: SimulationResult) extends Action

case class UpdateWorkloads(workloads: Map[TerminalName, Map[QueueName, QueuePaxAndWorkLoads]]) extends Action

case class GetWorkloads(begin: String, end: String) extends Action

case class GetAirportConfig() extends Action

case class UpdateAirportConfig(airportConfig: AirportConfig) extends Action

case class RunAllSimulations() extends Action

case class RunSimulation(terminalName: TerminalName, queueName: QueueName, desks: List[Int]) extends Action

case class SetShifts(shifts: String) extends Action

case class SaveShifts(shifts: String) extends Action

case class GetShifts() extends Action

case class AddShift(shift: Shift) extends Action

case class AddStaffMovement(staffMovement: StaffMovement) extends Action

case class RemoveStaffMovement(idx: Int, uUID: UUID) extends Action

case class SaveStaffMovements() extends Action

case class SetStaffMovements(staffMovements: Seq[StaffMovement]) extends Action

case class GetStaffMovements() extends Action

case class ProcessWork(desks: Seq[Double], workload: Seq[Double]) extends Action


trait WorkloadsUtil {
  def labelsFromAllQueues(startTime: Long) = {
    val oneMinute: Long = 60000
    val allMins = startTime until (startTime + 60000 * 60 * 24) by oneMinute

    allMins.map(millis => {
      val d = new js.Date(millis)
      f"${d.getHours()}%02d:${d.getMinutes()}%02d"
    })
  }

  def firstFlightTimeQueue(workloads: Map[String, (Seq[WL], Seq[Pax])]): Long = {
    workloads.values.flatMap(_._1.map(_.time)).min
  }

  def timeStampsFromAllQueues(workloads: Map[String, QueuePaxAndWorkLoads]) = {
    val timesMin: Long = firstFlightTimeQueue(workloads)
    minuteNumericRange(timesMin, 24)
  }

  def minuteNumericRange(start: Long, numberOfHours: Int = 24): NumericRange[Long] = {
    val oneMinute: Long = 60000
    val allMins = start until (start + 60000 * 60 * numberOfHours) by oneMinute
    allMins
  }
}

// The base model of our application
case class Workloads(workloads: Map[TerminalName, Map[QueueName, QueuePaxAndWorkLoads]]) extends WorkloadsUtil {
  lazy val labels = labelsFromAllQueues(startTime)

  def timeStamps(terminalName: TerminalName): NumericRange[Long] = minuteNumericRange(startTime, 24)

  def startTime: Long = {
    val now = new Date()
    val thisMorning = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    thisMorning.getTime().toLong
  }

  def firstFlightTimeAcrossTerminals: Long = workloads.values.map(firstFlightTimeQueue(_)).min
}

case class RootModel(
                      motd: Pot[String] = Empty,
                      workload: Pot[Workloads] = Empty,
                      queueCrunchResults: Map[TerminalName, Map[QueueName, Pot[PotCrunchResult]]] = Map(),
                      simulationResult: Map[TerminalName, Map[QueueName, Pot[SimulationResult]]] = Map(),
                      flights: Pot[Flights] = Empty,
                      airportInfos: Map[String, Pot[AirportInfo]] = Map(),
                      airportConfig: Pot[AirportConfig] = Empty,
                      minutesInASlot: Int = 15,
                      shiftsRaw: Pot[String] = Empty,
                      staffMovements: Seq[StaffMovement] = Seq(),
                      slotsInADay: Int = 96
                    ) {

  import TerminalUserDeskRecs._

  lazy val staffDeploymentsByTerminalAndQueue: Map[TerminalName, QueueStaffDeployments] = {
    val rawShiftsString = shiftsRaw match {
      case Ready(rawShifts) => rawShifts
      case _ => ""
    }

    val shifts = ShiftParser(rawShiftsString).parsedShifts.toList //todo we have essentially this code elsewhere, look for successfulShifts
    val staffFromShiftsAndMovementsAt = if (shifts.exists(s => s.isFailure)) {
      log.error("Couldn't parse raw shifts")
      (t: TerminalName, m: MilliDate) => 0
    } else {
      val successfulShifts = shifts.collect { case Success(s) => s }
      val ss = ShiftService(successfulShifts)
      StaffMovements.terminalStaffAt(ss)(staffMovements) _
    }

    val pdr = PortDeployment.portDeskRecs(queueCrunchResults)
    val pd = PortDeployment.terminalDeployments(pdr, staffFromShiftsAndMovementsAt)
    val tsa = PortDeployment.terminalStaffAvailable(pd) _

    StaffDeploymentCalculator(tsa, queueCrunchResults).getOrElse(Map())
  }

  lazy val calculatedDeploymentRows: Pot[Map[TerminalName, Pot[List[TerminalDeploymentsRow]]]] = {
    timeIt("calculateAllTerminalsRows")(calculateAllTerminalDeploymentRows)
  }

  def calculateAllTerminalDeploymentRows: Pot[Map[TerminalName, Pot[List[TerminalDeploymentsRow]]]] = {
    airportConfig.map(ac => ac.terminalNames.map(terminalName => {
      timeIt(s"calculateTerminalRows${terminalName}")(calculateTerminalDeploymentRows(terminalName))
    }).toMap)
  }

  def calculateTerminalDeploymentRows(terminalName: TerminalName): (TerminalName, Pot[List[TerminalDeploymentsRow]]) = {
    val crv = queueCrunchResults.getOrElse(terminalName, Map())
    val srv = simulationResult.getOrElse(terminalName, Map())
    val udr = staffDeploymentsByTerminalAndQueue.getOrElse(terminalName, Map())
    log.info(s"tud: ${terminalName}")
    val x: Pot[List[TerminalDeploymentsRow]] = workload.map(workloads => {
      val timestamps = workloads.timeStamps(terminalName)
      val startFromMilli = WorkloadsHelpers.midnightBeforeNow()
      val minutesRangeInMillis: NumericRange[Long] = WorkloadsHelpers.minutesForPeriod(startFromMilli, 24)
      val paxloads: Map[String, List[Double]] = WorkloadsHelpers.paxloadPeriodByQueue(workloads.workloads(terminalName), minutesRangeInMillis)
      TableViewUtils.terminalDeploymentsRows(timestamps, paxloads, crv, srv, udr)
    })
    terminalName -> x
  }

  lazy val calculatedRows: Pot[Map[TerminalName, Pot[List[TerminalUserDeskRecsRow]]]] = {
    timeIt("calculateAllTerminalsRows")(calculateAllTerminalsRows)
  }

  def calculateAllTerminalsRows: Pot[Map[TerminalName, Pot[List[TerminalUserDeskRecsRow]]]] = {
    airportConfig.map(ac => ac.terminalNames.map(terminalName => {
      timeIt(s"calculateTerminalRows${terminalName}")(calculateTerminalRows(terminalName))
    }).toMap)
  }

  def calculateTerminalRows(terminalName: TerminalName): (TerminalName, Pot[List[TableTerminalDeskRecs.TerminalUserDeskRecsRow]]) = {
    val crv = queueCrunchResults.getOrElse(terminalName, Map())
    val srv = simulationResult.getOrElse(terminalName, Map())
    val udr = staffDeploymentsByTerminalAndQueue.getOrElse(terminalName, Map())
    log.info(s"tud: ${terminalName}")
    val x: Pot[List[TerminalUserDeskRecsRow]] = workload.map(workloads => {
      val timestamps = workloads.timeStamps(terminalName)
      val startFromMilli = WorkloadsHelpers.midnightBeforeNow()
      val minutesRangeInMillis: NumericRange[Long] = WorkloadsHelpers.minutesForPeriod(startFromMilli, 24)
      val paxloads: Map[String, List[Double]] = WorkloadsHelpers.paxloadPeriodByQueue(workloads.workloads(terminalName), minutesRangeInMillis)
      TableViewUtils.terminalUserDeskRecsRows(timestamps, paxloads, crv, srv, udr)
    })
    terminalName -> x
  }

  override def toString: String =
    s"""
       |RootModel(
       |motd: $motd
       |paxload: $workload
       |queueCrunchResults: $queueCrunchResults
       |userDeskRec: $staffDeploymentsByTerminalAndQueue
       |simulationResult: $simulationResult
       |flights: $flights
       |airportInfos: $airportInfos
       |)
     """.stripMargin
}

object RootModel {

  def mergeTerminalQueues[A](m1: Map[QueueName, Map[QueueName, A]], m2: Map[QueueName, Map[QueueName, A]]): Map[String, Map[String, A]] = {
    val merged = m1.toSeq ++ m2.toSeq
    val grouped = merged.groupBy(_._1)
    val cleaned = grouped.mapValues(_.flatMap(_._2).toMap)
    cleaned
  }
}

case class DeskRecTimeSlots(items: Seq[DeskRecTimeslot]) {
  def updated(newItem: DeskRecTimeslot): DeskRecTimeSlots = {
    log.info(s"will update ${newItem} into ${items.take(5)}...")
    items.indexWhere(_.timeInMillis == newItem.timeInMillis) match {
      case -1 =>
        // add new
        log.info("add new")
        DeskRecTimeSlots(items :+ newItem)
      case idx =>
        log.info(s"add old: idx: $idx, newItem: $newItem, ${items(idx)}")
        // replace old
        DeskRecTimeSlots(items.updated(idx, newItem))
    }
  }
}

/**
  * Handles actions related to todos
  *
  * @param modelRW Reader/Writer to access the model
  */
class DeskTimesHandler[M](modelRW: ModelRW[M, Map[TerminalName, QueueStaffDeployments]]) extends LoggingActionHandler(modelRW) {
  override def handle = {
    case UpdateDeskRecsTime(terminalName, queueName, deskRecTimeSlot) =>
      val newDesksPot: Pot[DeskRecTimeSlots] = value(terminalName)(queueName).map(_.updated(deskRecTimeSlot))
      val desks = newDesksPot.get.items.map(_.deskRec).toList
      updated(
        mergeTerminalQueues(value, Map(terminalName -> Map(queueName -> newDesksPot))),
        Effect(Future(RunSimulation(terminalName, queueName, desks))))
  }
}

abstract class LoggingActionHandler[M, T](modelRW: ModelRW[M, T]) extends ActionHandler(modelRW) {
  override def handleAction(model: M, action: Any): Option[ActionResult[M]] = {
    log.info(s"finding handler for ${action.toString.take(100)}")
    Try(super.handleAction(model, action)) match {
      case Failure(f) =>
        log.error(s"Exception from ${getClass}  ${f.getMessage}")
        throw f
      case Success(s) =>
        s
    }
  }
}

class AirportConfigHandler[M](modelRW: ModelRW[M, Pot[AirportConfig]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case action: GetAirportConfig =>
      log.info("requesting workloadsWrapper from server")

      updated(Pending(), Effect(AjaxClient[Api].airportConfiguration().call().map(UpdateAirportConfig)))
    case UpdateAirportConfig(configHolder) =>
      log.info(s"Received airportConfig $configHolder")
      log.info("Subscribing to crunches for terminal/queues")

      val effects: Effect = createCrunchRequestEffects(configHolder)
      updated(Ready(configHolder), effects)
  }

  def createCrunchRequestEffects(configHolder: AirportConfig): Effect = {
    val crunchRequests: Seq[Effect] = for {tn <- configHolder.terminalNames
                                           qn <- configHolder.queues
    } yield {
      Effect(Future(GetLatestCrunch(tn, qn)))
    }

    val effects = seqOfEffectsToEffectSeq(crunchRequests)
    effects
  }
}

class WorkloadHandler[M](modelRW: ModelRW[M, Pot[Workloads]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case action: GetWorkloads =>
      log.info("requesting workloadsWrapper from server")
      updated(Pending(), Effect(AjaxClient[Api].getWorkloads().call().map(UpdateWorkloads)))

    case UpdateWorkloads(terminalQueueWorkloads) =>
      updated(Ready(Workloads(terminalQueueWorkloads)))
  }
}

object HandyStuff {
  type PotCrunchResult = Pot[CrunchResult]
  type QueueStaffDeployments = Map[String, Pot[DeskRecTimeSlots]]
  type TerminalQueueStaffDeployments = Map[TerminalName, QueueStaffDeployments]

  def seqOfEffectsToEffectSeq(crunchRequests: Seq[Effect]) = {
    crunchRequests.toList match {
      case h :: Nil =>
        h
      case h :: ts =>
        new EffectSeq(h, ts, queue)
    }
  }
}

class SimulationHandler[M](staffDeployments: ModelR[M, TerminalQueueStaffDeployments],
                           modelR: ModelR[M, Pot[Workloads]],
                           modelRW: ModelRW[M, Map[TerminalName, Map[QueueName, Pot[SimulationResult]]]])
  extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case RunAllSimulations() =>
      val actions = staffDeployments.value.flatMap {
        case (terminalName, queueMapPot) => {
          queueMapPot.map {
            case (queueName, deployedDesks) =>
              val queueWorkload: List[Double] = getTerminalQueueWorkload(terminalName, queueName)

              val desks = deployedDesks.get.items.map(_.deskRec).toList
              Effect(Future(RunSimulation(terminalName, queueName, desks)))
          }
        }
      }.toList

      effectOnly(seqOfEffectsToEffectSeq(actions))

    case RunSimulation(terminalName, queueName, desks) =>
      log.info(s"Requesting simulation for $terminalName, {queueName}")
      val queueWorkload: List[Double] = getTerminalQueueWorkload(terminalName, queueName)
      val simulationResult: Future[SimulationResult] = AjaxClient[Api].processWork(terminalName, queueName, queueWorkload, desks).call()
      effectOnly(
        Effect(simulationResult.map(resp => UpdateSimulationResult(terminalName, queueName, resp)))
      )
    case UpdateSimulationResult(terminalName, queueName, simResult) =>
      updated(mergeTerminalQueues(value, Map(terminalName -> Map(queueName -> Ready(simResult)))))
  }

  private def getTerminalQueueWorkload(terminalName: TerminalName, queueName: QueueName) = {
    val startFromMilli = WorkloadsHelpers.midnightBeforeNow()
    val minutesRangeInMillis: NumericRange[Long] = WorkloadsHelpers.minutesForPeriod(startFromMilli, 24)
    val terminalWorkload = modelR.value.get.workloads(terminalName)
    val queueWorkload: List[Double] = WorkloadsHelpers.workloadPeriodByQueue(terminalWorkload, minutesRangeInMillis)(queueName)
    queueWorkload
  }
}

case class GetLatestCrunch(terminalName: TerminalName, queueName: QueueName) extends Action

case class RequestFlights(from: Long, to: Long) extends Action

case class UpdateFlights(flights: Flights) extends Action

class FlightsHandler[M](modelRW: ModelRW[M, Pot[Flights]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case RequestFlights(from, to) =>
      log.info(s"client requesting flights $from $to")
      val flightsEffect = Effect(Future(RequestFlights(0, 0))).after(10L seconds)
      val fe: EffectSingle[UpdateFlights] = Effect(AjaxClient[Api].flights(from, to).call().map(UpdateFlights))
      effectOnly(fe + flightsEffect)
    case UpdateFlights(flights) =>
      log.info(s"client got ${flights.flights.length} flights")
      val result = if (value.isReady) {
        val oldFlights = value.get
        val oldFlightsSet = oldFlights.flights.toSet
        val newFlightsSet = flights.flights.toSet
        if (oldFlightsSet != newFlightsSet) {
          val i: Flights = flights
          val j: List[ApiFlight] = flights.flights
          val codes = flights.flights.map(_.Origin).toSet
          updated(Ready(flights), Effect(Future(GetAirportInfos(codes))))
        } else {
          log.info("no changes to flights")
          noChange
        }
      } else {
        val codes = flights.flights.map(_.Origin).toSet
        updated(Ready(flights), Effect(Future(GetAirportInfos(codes))))
      }

      result
  }
}

class CrunchHandler[M](modelRW: ModelRW[M, Map[TerminalName, Map[QueueName, Pot[PotCrunchResult]]]])
  extends LoggingActionHandler(modelRW) {

  def modelQueueCrunchResults = value

  override def handle = {
    case GetLatestCrunch(terminalName, queueName) =>
      val crunchEffect = Effect(Future(GetLatestCrunch(terminalName, queueName))).after(10L seconds)
      val fe: Future[Action] = AjaxClient[Api].getLatestCrunchResult(terminalName, queueName).call().map {
        case Right(crunchResultWithTimeAndInterval) =>
          UpdateCrunchResult(terminalName, queueName, crunchResultWithTimeAndInterval)
        case Left(ncr) =>
          log.info(s"Failed to fetch crunch - has a crunch run yet? $ncr")
          NoAction
      }
      effectOnly(Effect(fe) + crunchEffect)
    case UpdateCrunchResult(terminalName, queueName, crunchResultWithTimeAndInterval) =>

      log.info(s"UpdateCrunchResult $queueName. firstTimeMillis: ${crunchResultWithTimeAndInterval.firstTimeMillis}")

      val crunchResultsByQueue = mergeTerminalQueues(value, Map(terminalName -> Map(queueName -> Ready(Ready(crunchResultWithTimeAndInterval)))))

      if (modelQueueCrunchResults != crunchResultsByQueue) {
        updated(crunchResultsByQueue)
      } else
        noChange
  }
}

object StaffDeploymentCalculator {
  type TerminalQueueStaffDeployments = Map[TerminalName, QueueStaffDeployments]

  def apply[M](staffAvailable: (TerminalName) => (MilliDate) => Int, terminalQueueCrunchResultsModel: Map[TerminalName, QueueCrunchResults]):
  Try[TerminalQueueStaffDeployments] = {

    val terminalQueueCrunchResults = terminalQueueCrunchResultsModel
    val firstTerminalName = terminalQueueCrunchResults.keys.headOption.getOrElse("")
    val crunchResultWithTimeAndIntervalTry = Try(terminalQueueCrunchResults(firstTerminalName).head._2.get.get)

    crunchResultWithTimeAndIntervalTry.map(crunchResultWithTimeAndInterval => {

      val drts: DeskRecTimeSlots = calculateDeskRecTimeSlots(crunchResultWithTimeAndInterval)

      val newSuggestedStaffDeployments: Map[TerminalName, Map[QueueName, Ready[DeskRecTimeSlots]]] = terminalQueueCrunchResults.map((terminalQueueCrunchResult: (TerminalName, QueueCrunchResults)) => {
        val terminalName: TerminalName = terminalQueueCrunchResult._1
        val terminalStaffAvailable = staffAvailable(terminalName)
        val queueCrunchResult = terminalQueueCrunchResult._2

        val queueDeskRecsOverTime: Iterable[Iterable[DeskRecTimeslot]] = queueCrunchResult.transpose {
          case (_, Ready(Ready(cr))) => calculateDeskRecTimeSlots(cr).items
        }

        val timeslotsToInts = (deskRecTimeSlots: Iterable[DeskRecTimeslot]) => {
          val timeInMillis = MilliDate(deskRecTimeSlots.headOption.map(_.timeInMillis).getOrElse(0L))
          queueRecsToDeployments(_.toInt)(deskRecTimeSlots.map(_.deskRec).toList, terminalStaffAvailable(timeInMillis))
        }
        val deployments = queueDeskRecsOverTime.map(timeslotsToInts).transpose

        val times: Seq[Long] = drts.items.map(_.timeInMillis)

        val zipped = queueCrunchResult.keys.zip({
          deployments.map(times.zip(_).map { case (t, r) => DeskRecTimeslot(t, r) })
        })

        (terminalName, zipped.toMap.mapValues((x: Seq[DeskRecTimeslot]) => Ready(DeskRecTimeSlots(x))))
      })

      newSuggestedStaffDeployments
    })

  }

  def calculateDeskRecTimeSlots(crunchResultWithTimeAndInterval: CrunchResult) = {
    val timeIntervalMinutes = 15
    val millis = Iterator.iterate(crunchResultWithTimeAndInterval.firstTimeMillis)(_ + timeIntervalMinutes * crunchResultWithTimeAndInterval.intervalMillis).toIterable

    val updatedDeskRecTimeSlots: DeskRecTimeSlots = DeskRecTimeSlots(
      DeskRecsChart
        .takeEveryNth(timeIntervalMinutes)(crunchResultWithTimeAndInterval.recommendedDesks)
        .zip(millis).map {
        case (deskRec, timeInMillis) => DeskRecTimeslot(timeInMillis = timeInMillis, deskRec = deskRec)
      }.toList)
    updatedDeskRecTimeSlots
  }

  def queueRecsToDeployments(round: Double => Int)(queueRecs: Seq[Int], staffAvailable: Int): Seq[Int] = {
    val totalStaffRec = queueRecs.sum
    queueRecs.foldLeft(List[Int]()) {
      case (agg, queueRec) if (agg.length < queueRecs.length - 1) =>
        agg :+ round(staffAvailable * (queueRec.toDouble / totalStaffRec))
      case (agg, _) =>
        agg :+ staffAvailable - agg.sum
    }
  }
}

class AirportCountryHandler[M](timeProvider: () => Long, modelRW: ModelRW[M, Map[String, Pot[AirportInfo]]]) extends LoggingActionHandler(modelRW) {
  def mkPending = Pending(timeProvider())

  override def handle = {
    case GetAirportInfos(codes) =>
      val stringToObject: Map[String, Pot[AirportInfo]] = value ++ Map("BHX" -> mkPending, "EDI" -> mkPending)
      updated(stringToObject, Effect(AjaxClient[Api].airportInfosByAirportCodes(codes).call().map(UpdateAirportInfos(_))))
    case UpdateAirportInfos(infos) =>
      val infosReady = infos.map(kv => (kv._1, Ready(kv._2)))
      updated(value ++ infosReady)
    case GetAirportInfo(code) =>
      value.get(code) match {
        case None =>
          val stringToObject = value + (code -> Empty)
          log.info(s"sending request for info for ${code}")
          updated(stringToObject, Effect(AjaxClient[Api].airportInfoByAirportCode(code).call().map(res => UpdateAirportInfo(code, res))))
        case Some(v) =>
          noChange
      }
    case UpdateAirportInfo(code, Some(airportInfo)) =>
      val newValue = value + ((code -> Ready(airportInfo)))
      log.info(s"got a new value for ${code} ${airportInfo}")
      updated(newValue)
  }
}

class ShiftsHandler[M](modelRW: ModelRW[M, Pot[String]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case SetShifts(shifts: String) =>
      updated(Ready(shifts), Effect(Future(RunAllSimulations())))
    case SaveShifts(shifts: String) =>
      AjaxClient[Api].saveShifts(shifts).call()
      noChange
    case AddShift(shift) =>
      updated(Ready(s"${value.getOrElse("")}\n${shift.toCsv}"))
    case GetShifts() =>
      effectOnly(Effect(AjaxClient[Api].getShifts().call().map(res => SetShifts(res))))
  }
}

class StaffMovementsHandler[M](modelRW: ModelRW[M, Seq[StaffMovement]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case AddStaffMovement(staffMovement) =>
      val v: Seq[StaffMovement] = value
      val updatedValue: Seq[StaffMovement] = (v :+ staffMovement).sortBy(_.time)
      updated(updatedValue)
    case RemoveStaffMovement(idx, uUID) =>
      val updatedValue = value.filter(_.uUID != uUID)
      updated(updatedValue, Effect(Future(SaveStaffMovements())))
    case SetStaffMovements(staffMovements: Seq[StaffMovement]) =>
      updated(staffMovements, Effect(Future(RunAllSimulations())))
    case GetStaffMovements() =>
      effectOnly(Effect(AjaxClient[Api].getStaffMovements().call().map(res => SetStaffMovements(res))))
    case SaveStaffMovements() =>
      AjaxClient[Api].saveStaffMovements(value).call()
      noChange
  }
}

trait DrtCircuit extends Circuit[RootModel] with ReactConnector[RootModel] {
  val blockWidth = 15

  def timeProvider() = new Date().getTime.toLong

  // initial application model
  override protected def initialModel = RootModel()

  // combine all handlers into one
  override val actionHandler = {
    println("composing handlers")
    composeHandlers(
      new WorkloadHandler(zoomRW(_.workload)((m, v) => {
        m.copy(workload = v)
      })),
      new CrunchHandler(zoomRW(m => m.queueCrunchResults)((m, v) => m.copy(queueCrunchResults = v))),
      new SimulationHandler(zoom(_.staffDeploymentsByTerminalAndQueue), zoom(_.workload), zoomRW(_.simulationResult)((m, v) => m.copy(simulationResult = v))),
      new FlightsHandler(zoomRW(_.flights)((m, v) => m.copy(flights = v))),
      new AirportCountryHandler(timeProvider, zoomRW(_.airportInfos)((m, v) => m.copy(airportInfos = v))),
      new AirportConfigHandler(zoomRW(_.airportConfig)((m, v) => m.copy(airportConfig = v))),
      new ShiftsHandler(zoomRW(_.shiftsRaw)((m, v) => m.copy(shiftsRaw = v))),
      new StaffMovementsHandler(zoomRW(_.staffMovements)((m, v) => m.copy(staffMovements = v)))
    )
  }
}

object SPACircuit extends DrtCircuit

case class GetAirportInfos(code: Set[String]) extends Action

case class GetAirportInfo(code: String) extends Action

case class UpdateAirportInfo(code: String, info: Option[AirportInfo]) extends Action

case class UpdateAirportInfos(infos: Map[String, AirportInfo]) extends Action

