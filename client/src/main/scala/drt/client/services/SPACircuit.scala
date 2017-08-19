package drt.client.services


import java.nio.ByteBuffer

import autowire._
import diode.Implicits.runAfterImpl
import diode._
import diode.data._
import diode.react.ReactConnector
import drt.client.{SPAMain, TableViewUtils}
import drt.client.logger._
import drt.client.services.HandyStuff._
import drt.client.services.RootModel.{FlightCode, QueueCrunchResults, mergeTerminalQueues}
import drt.shared.FlightsApi.{TerminalName, _}
import drt.shared._
import boopickle.Default._
import diode.ActionResult.NoChange
import drt.client.services.JSDateConversions.SDate
import drt.client.services.JSDateConversions.SDate.JSSDate
import drt.shared.PassengerSplits.{FlightNotFound, VoyagePaxSplits}
import drt.client.components.TerminalDeploymentsTable.TerminalDeploymentsRow
import drt.client.actions.Actions._
import drt.client.components.FlightsWithSplitsTable
import drt.shared.Simulations.{QueueSimulationResult, TerminalSimulationResultsFull}

import scala.collection.immutable.{Iterable, Map, NumericRange, Seq}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.Date
import scala.util.{Failure, Success, Try}

case class DeskRecTimeslot(timeInMillis: Long, deskRec: Int)

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

  def minuteNumericRange(start: Long, numberOfHours: Int): NumericRange[Long] = {
    val oneMinute: Long = 60000
    val allMins = start until (start + 60000 * 60 * numberOfHours) by oneMinute
    allMins
  }
}

// The base model of our application
case class Workloads(workloads: Map[TerminalName, Map[QueueName, QueuePaxAndWorkLoads]]) extends WorkloadsUtil {
  lazy val labels = labelsFromAllQueues(startTime)

  def timeStamps(): NumericRange[Long] = minuteNumericRange(startTime, 24)

  def startTime: Long = {
    val now = new Date()
    val thisMorning = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    thisMorning.getTime().toLong
  }

  def firstFlightTimeAcrossTerminals: Long = workloads.values.map(firstFlightTimeQueue).min
}

case class RootModel(
                      motd: Pot[String] = Empty,
                      workloadPot: Pot[Workloads] = Empty,
                      queueCrunchResults: RootModel.PortCrunchResults = Map(),
                      simulationResult: RootModel.PortSimulationResults = Map(),
                      flightsWithSplitsPot: Pot[FlightsWithSplits] = Empty,
                      airportInfos: Map[String, Pot[AirportInfo]] = Map(),
                      airportConfig: Pot[AirportConfig] = Empty,
                      minutesInASlot: Int = 15,
                      shiftsRaw: Pot[String] = Empty,
                      fixedPointsRaw: Pot[String] = Empty,
                      staffMovements: Pot[Seq[StaffMovement]] = Empty,
                      slotsInADay: Int = 96,
                      actualDeskStats: Map[TerminalName, Map[QueueName, Map[Long, DeskStat]]] = Map(),
                      pointInTime: Option[SDateLike] = None
                    ) {

  lazy val staffDeploymentsByTerminalAndQueue: Map[TerminalName, QueueStaffDeployments] = {
    val rawShiftsString = shiftsRaw match {
      case Ready(rawShifts) => rawShifts
      case _ => ""
    }
    val rawFixedPointsString = fixedPointsRaw match {
      case Ready(rawFixedPoints) => rawFixedPoints
      case _ => ""
    }

    val movements = staffMovements match {
      case Ready(mm) => mm
      case _ => Seq()
    }

    val shifts = StaffAssignmentParser(rawShiftsString).parsedAssignments.toList
    val fixedPoints = StaffAssignmentParser(rawFixedPointsString).parsedAssignments.toList
    //todo we have essentially this code elsewhere, look for successfulShifts
    val staffFromShiftsAndMovementsAt = if (shifts.exists(s => s.isFailure) || fixedPoints.exists(s => s.isFailure)) {
      (t: TerminalName, m: MilliDate) => 0
    } else {
      val successfulShifts = shifts.collect { case Success(s) => s }
      val ss = StaffAssignmentServiceWithDates(successfulShifts)

      val successfulFixedPoints = fixedPoints.collect { case Success(s) => s }
      val fps = StaffAssignmentServiceWithoutDates(successfulFixedPoints)
      StaffMovements.terminalStaffAt(ss, fps)(movements) _
    }

    val pdr = PortDeployment.portDeskRecs(queueCrunchResults)
    val pd = PortDeployment.terminalDeployments(pdr, staffFromShiftsAndMovementsAt)
    val tsa = PortDeployment.terminalStaffAvailable(pd) _

    airportConfig match {
      case Ready(config) =>
        StaffDeploymentCalculator(tsa, queueCrunchResults, config.minMaxDesksByTerminalQueue).getOrElse(Map())
      case _ => Map()
    }
  }

  override def toString: String =
    s"""
       |RootModel(
       |motd: $motd
       |paxload: $workloadPot
       |queueCrunchResults: $queueCrunchResults
       |userDeskRec: $staffDeploymentsByTerminalAndQueue
       |simulationResult: $simulationResult
       |flights: $flightsWithSplitsPot
       |airportInfos: $airportInfos
       |)
     """.stripMargin
}

object RootModel {
  type TerminalSimulationResults = Map[QueueName, QueueSimulationResult]
  type PortSimulationResults = Map[TerminalName, TerminalSimulationResults]
  type PortCrunchResults = Map[TerminalName, Map[QueueName, CrunchResult]]

  type FlightCode = String

  type QueueCrunchResults = Map[QueueName, CrunchResult]

  def mergeTerminalQueues[A](m1: Map[QueueName, Map[QueueName, A]], m2: Map[QueueName, Map[QueueName, A]]): Map[String, Map[String, A]] = {
    val merged = m1.toSeq ++ m2.toSeq
    val grouped = merged.groupBy(_._1)
    val cleaned = grouped.mapValues(_.flatMap(_._2).toMap)
    cleaned
  }
}

case class DeskRecTimeSlots(items: Seq[DeskRecTimeslot]) {
  def updated(newItem: DeskRecTimeslot): DeskRecTimeSlots = {
    items.indexWhere(_.timeInMillis == newItem.timeInMillis) match {
      case -1 => DeskRecTimeSlots(items :+ newItem)
      case idx => DeskRecTimeSlots(items.updated(idx, newItem))
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
    case udrt@UpdateDeskRecsTime(terminalName, queueName, deskRecTimeSlot) =>
      val newDesksPot: Pot[DeskRecTimeSlots] = value(terminalName)(queueName).map(_.updated(deskRecTimeSlot))
      val desks = newDesksPot.get.items.map(_.deskRec).toList
      updated(
        mergeTerminalQueues(value, Map(terminalName -> Map(queueName -> newDesksPot))),
        Effect(Future(RunSimulation(terminalName, queueName, desks))))
  }
}

abstract class LoggingActionHandler[M, T](modelRW: ModelRW[M, T]) extends ActionHandler(modelRW) {
  override def handleAction(model: M, action: Any): Option[ActionResult[M]] = {
    Try(super.handleAction(model, action)) match {
      case Failure(f) =>
        f.getCause() match {
          case null => log.error(s"no cause")
          case c => log.error(s"Exception from $getClass  ${c.getMessage}")
        }

        throw f
      case Success(s) =>
        s
    }
  }
}

class AirportConfigHandler[M](modelRW: ModelRW[M, Pot[AirportConfig]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case _: GetAirportConfig =>
      updated(Pending(), Effect(AjaxClient[Api].airportConfiguration().call().map(UpdateAirportConfig)))
    case UpdateAirportConfig(configHolder) =>
      updated(Ready(configHolder))
  }
}

class WorkloadsHandler[M](modelRW: ModelRW[M, Pot[Workloads]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case GetWorkloads(_, _) =>
      val newWorkloads = if (value.isEmpty) Pending() else value
      updated(newWorkloads,
        Effect(AjaxClient[Api].getWorkloads().call().map {
          case Left(WorkloadsNotReady()) => GetWorkloads("", "")
          case Right(wl) => UpdateWorkloads(wl)
        }))
    case UpdateWorkloads(terminalQueueWorkloads: PortPaxAndWorkLoads[(Seq[WL], Seq[Pax])]) =>
      val roundedTimesToMinutes: Map[TerminalName, Map[QueueName, (Seq[WL], Seq[Pax])]] = {
        terminalQueueWorkloads.mapValues(q => q.mapValues(plWl =>
          (plWl._1.map(pl => WL(timeFromMillisToNearestSecond(pl.time), pl.workload)),
            plWl._2.map(pl => Pax(timeFromMillisToNearestSecond(pl.time), pl.pax)))))
      }
      val changedTerminals = roundedTimesToMinutes.keys.filter(terminalName => {
        val newTerminalWl = roundedTimesToMinutes(terminalName)
        val noExistingWorkloads = !value.isReady
        noExistingWorkloads || newTerminalWl != value.get.workloads(terminalName)
      })
      val crunchRequests = changedTerminals
        .map(terminalName => {
          log.info(s"$terminalName workloads changed. Requesting crunch")
          Effect(Future(GetTerminalCrunch(terminalName)))
        })
        .toList
      if (crunchRequests.isEmpty)
        updated(Ready(Workloads(roundedTimesToMinutes)))
      else
        updated(Ready(Workloads(roundedTimesToMinutes)), seqOfEffectsToEffectSeq(crunchRequests))
  }

  private def timeFromMillisToNearestSecond(time: Long) = {
    Math.round(time.toDouble / 60000) * 60000
  }
}

object HandyStuff {
  type PotCrunchResult = Pot[CrunchResult]
  type QueueStaffDeployments = Map[String, Pot[DeskRecTimeSlots]]
  type TerminalQueueStaffDeployments = Map[TerminalName, QueueStaffDeployments]

  def seqOfEffectsToEffectSeq(crunchRequests: List[Effect]): Effect = {
    crunchRequests match {
      case Nil =>
        Effect(Future {
          NoAction
        })
      case h :: Nil =>
        h
      case h :: ts =>
        new EffectSeq(h, ts, queue)
    }
  }
}

class SimulationHandler[M](
                            airportConfig: ModelR[M, Pot[AirportConfig]],
                            allQueueCrunchesReceived: () => Boolean,
                            rawShifts: ModelR[M, Pot[String]],
                            rawFixedPoints: ModelR[M, Pot[String]],
                            movements: ModelR[M, Pot[Seq[StaffMovement]]],
                            staffDeployments: ModelR[M, TerminalQueueStaffDeployments],
                            modelR: ModelR[M, Pot[Workloads]],
                            modelRW: ModelRW[M, Map[TerminalName, Map[QueueName, QueueSimulationResult]]])
  extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case RunAllSimulations() =>
      val effects: List[EffectSingle[RunTerminalSimulation]] = if (airportConfig.value.isReady) {
        airportConfig.value.get.terminalNames.map(terminalName => Effect(Future(RunTerminalSimulation(terminalName)))).toList
      } else List()
      effectOnly(seqOfEffectsToEffectSeq(effects))
    case RunTerminalSimulation(terminalName) =>
      if (rawShifts.value.isReady && rawFixedPoints.value.isReady && movements.value.isReady && allQueueCrunchesReceived() && airportConfig.value.isReady) {
        log.info(s"Requesting simulation for $terminalName")
        val terminalDeskRecs = staffDeployments.value.getOrElse(terminalName, Map()).map {
          case (queueName, drtsPot) => queueName -> drtsPot.map(_.items.map(_.deskRec)).getOrElse(List()).toList
        }
        val terminalWorkloads = getTerminalWorkload(terminalName)
        val simulationResult: Future[TerminalSimulationResultsFull] = AjaxClient[Api].getTerminalSimulations(terminalName, terminalWorkloads, terminalDeskRecs).call()
        effectOnly(Effect(simulationResult.map(resp => UpdateTerminalSimulation(terminalName, resp))))
      } else {
        log.info(s"Not running simulation for $terminalName. Shifts, Fixed points, movements, crunches & airport config not all ready yet")
        noChange
      }
    case UpdateTerminalSimulation(terminalName, terminalSimulationResultsFull) =>
      updated(value + (terminalName -> terminalSimulationResultsFull))
  }

  private def getTerminalWorkload(terminalName: TerminalName): Map[QueueName, List[Double]] = {
    val startFromMilli = WorkloadsHelpers.midnightBeforeNow()
    val minutesRangeInMillis: NumericRange[Long] = WorkloadsHelpers.minutesForPeriod(startFromMilli, 24)
    val workloadPot = modelR.value
    workloadPot match {
      case Ready(workload) =>
        workload.workloads.get(terminalName) match {
          case Some(terminalWorkload) => WorkloadsHelpers.workloadPeriodByQueue(terminalWorkload, minutesRangeInMillis)
          case None => Map()
        }
      case _ => Map()
    }
  }
}

case class GetLatestCrunch(terminalName: TerminalName, queueName: QueueName) extends Action

case class RequestFlights() extends Action

case class UpdateFlights(flights: Flights) extends Action

case class UpdateFlightsWithSplits(flights: FlightsWithSplits) extends Action

case class UpdateFlightPaxSplits(splitsEither: Either[FlightNotFound, VoyagePaxSplits]) extends Action

class FlightsHandler[M](pointInTime: ModelR[M, Option[SDateLike]], modelRW: ModelRW[M, Pot[FlightsWithSplits]]) extends LoggingActionHandler(modelRW) {
  val flightsRequestFrequency = 60 seconds

  def pointInTimeMillis = pointInTime.value.map(_.millisSinceEpoch).getOrElse(0L)

  protected def handle = {
    case RequestFlights() =>
      log.info(s"Requesting flights for point in time ${SDate(MilliDate(pointInTimeMillis)).toLocalDateTimeString} - $pointInTime")
      val x = AjaxClient[Api].flightsWithSplits(pointInTimeMillis, 0).call().map {
        case Right(fs) =>
          log.info(s"Got ${fs.flights.length} flights!")
          UpdateFlightsAndContinuePolling(fs)
        case Left(FlightsNotReady()) =>
          log.info(s"Flights not ready")
          RequestFlightsAfter(10)
      }
      effectOnly(Effect(x))

    case UpdateFlightsAndContinuePolling(flights: FlightsWithSplits) =>
      val requestFlightsAfterDelay = Effect(Future(RequestFlights())).after(flightsRequestFrequency)
      val updateFlights = Effect(Future(UpdateFlightsWithSplits(flights)))
      effectOnly(updateFlights + requestFlightsAfterDelay)

    case RequestFlightsAfter(delaySeconds: Int) =>
      log.info(s"Re-requesting in $delaySeconds seconds")
      val requestFlightsAfterDelay = Effect(Future(RequestFlights())).after(delaySeconds seconds)
      effectOnly(requestFlightsAfterDelay)

    case UpdateFlightsWithSplits(flightsWithSplits) =>
      log.info(s"client got ${flightsWithSplits.flights.length} flights")
      val flights = flightsWithSplits.flights.map(_.apiFlight)

      val result = if (value.isReady) {
        val oldFlights = value.get
        val oldFlightsSet = oldFlights.flights.toSet
        val newFlightsSet = flightsWithSplits.flights.toSet
        if (oldFlightsSet != newFlightsSet) {
          val airportCodes = flights.map(_.Origin).toSet
          val airportInfos = Effect(Future(GetAirportInfos(airportCodes)))

          val getWorkloads = Effect(Future {
            log.info("flights Have changed - re-requesting workloads")
            GetWorkloads("", "")
          })

          val allEffects = airportInfos >> getWorkloads
          updated(Ready(flightsWithSplits), allEffects)
        } else {
          log.info("no changes to flights")
          noChange
        }
      } else {
        val airportCodes = flights.map(_.Origin).toSet
        updated(Ready(flightsWithSplits), Effect(Future(GetAirportInfos(airportCodes))))
      }
      result
    case UpdateFlightPaxSplits(Left(failure)) =>
      log.info(s"Did not find flightPaxSplits for ${failure}")
      noChange
    case UpdateFlightPaxSplits(Right(result)) =>
      log.info(s"Found flightPaxSplits ${result}")
      noChange
  }

}

class CrunchHandler[M](totalQueues: () => Map[TerminalName, Int], modelRW: ModelRW[M, Map[TerminalName, Map[QueueName, CrunchResult]]],
                       staffDeployments: ModelR[M, TerminalQueueStaffDeployments],
                       airportConfig: ModelR[M, Pot[AirportConfig]])
  extends LoggingActionHandler(modelRW) {

  def modelQueueCrunchResults = value

  override def handle = {
    case GetTerminalCrunch(terminalName) =>
      val callResultFuture: Future[List[(QueueName, Either[NoCrunchAvailable, CrunchResult])]] = AjaxClient[Api].getTerminalCrunchResult(terminalName).call()

      val updateTerminalAction: Future[UpdateTerminalCrunchResult] = callResultFuture.map {
        case qncrs =>
          UpdateTerminalCrunchResult(terminalName, qncrs.collect {
            case (queueName, Right(cr)) => queueName -> cr
          }.toMap)
      }

      effectOnly(Effect(updateTerminalAction))

    case UpdateTerminalCrunchResult(terminalName, tqr) =>
      val oldTC = value.getOrElse(terminalName, Map())
      val newTC = Map(terminalName -> tqr)

      val terminalQueues = totalQueues().getOrElse(terminalName, -1)
      val effects = if (tqr.size < terminalQueues) {
        log.info(s"Only received ${tqr.size} out of ${totalQueues()} queue crunch results. Requesting another terminal crunch in 5 seconds")
        List(Effect(Future(GetTerminalCrunch(terminalName))).after(5 seconds))
      } else List()

      if (newTC != oldTC) {
        log.info(s"crunch for $terminalName has updated. requesting simulation result")
        val runSimulation = Effect(Future(RunTerminalSimulation(terminalName)))
        val allEffects = seqOfEffectsToEffectSeq(runSimulation :: effects)
        updated(value ++ newTC, allEffects)
      } else {
        log.info(s"crunch for $terminalName has not updated")
        effectOnly(seqOfEffectsToEffectSeq(effects))
      }
  }
}

object StaffDeploymentCalculator {
  type TerminalQueueStaffDeployments = Map[TerminalName, QueueStaffDeployments]

  def apply[M](
                staffAvailable: (TerminalName) => (MilliDate) => Int,
                terminalQueueCrunchResultsModel: Map[TerminalName, QueueCrunchResults],
                queueMinAndMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]]
              ):
  Try[TerminalQueueStaffDeployments] = {

    val terminalQueueCrunchResults = terminalQueueCrunchResultsModel
    val firstTerminalName = terminalQueueCrunchResults.keys.headOption.getOrElse("")
    val crunchResultWithTimeAndIntervalTry = Try(terminalQueueCrunchResults(firstTerminalName).head._2)

    crunchResultWithTimeAndIntervalTry.map(crunchResultWithTimeAndInterval => {

      val drts: DeskRecTimeSlots = calculateDeskRecTimeSlots(crunchResultWithTimeAndInterval)

      val newSuggestedStaffDeployments: Map[TerminalName, Map[QueueName, Ready[DeskRecTimeSlots]]] = terminalQueueCrunchResults.map((terminalQueueCrunchResult: (TerminalName, QueueCrunchResults)) => {
        val terminalName: TerminalName = terminalQueueCrunchResult._1
        val terminalStaffAvailable = staffAvailable(terminalName)
        val queueCrunchResult = terminalQueueCrunchResult._2

        println(s"Got crunch results for queues: ${queueCrunchResult.keys}")

        /*
         Fixme: This transpose loses the queue name and thus certainty of order
         */
        val queueDeskRecsOverTime: Iterable[Iterable[DeskRecTimeslot]] = queueCrunchResult.transpose {
          case (_, cr) => calculateDeskRecTimeSlots(cr).items
        }
        val timeslotsToInts = (deskRecTimeSlots: Iterable[DeskRecTimeslot]) => {
          val timeInMillis = MilliDate(deskRecTimeSlots.headOption.map(_.timeInMillis).getOrElse(0L))
          val queueNames = terminalQueueCrunchResults(terminalName).keys
          val deskRecs: Iterable[(Int, QueueName)] = deskRecTimeSlots.map(_.deskRec).zip(queueNames)
          val deps = queueRecsToDeployments(_.toInt)(deskRecs.toList, terminalStaffAvailable(timeInMillis), minMaxDesksForTime(queueMinAndMaxDesks(terminalName), timeInMillis.millisSinceEpoch))
          deps
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

  def minMaxDesksForTime(minMaxDesks: Map[QueueName, (List[Int], List[Int])], timestamp: Long): Map[QueueName, (Int, Int)] = {
    import JSDateConversions._
    val hour = MilliDate(timestamp).getHours()
    minMaxDesks.mapValues(minMaxForQueue => (minMaxForQueue._1(hour), minMaxForQueue._2(hour)))
  }

  def deploymentWithinBounds(min: Int, max: Int, ideal: Int, staffAvailable: Int) = {
    val best = if (ideal < min) min
    else if (ideal > max) max
    else ideal

    if (best > staffAvailable) staffAvailable
    else best
  }

  def calculateDeskRecTimeSlots(crunchResultWithTimeAndInterval: CrunchResult) = {
    val timeIntervalMinutes = 15
    val millis = Iterator.iterate(crunchResultWithTimeAndInterval.firstTimeMillis)(_ + timeIntervalMinutes * crunchResultWithTimeAndInterval.intervalMillis).toIterable

    val updatedDeskRecTimeSlots: DeskRecTimeSlots = DeskRecTimeSlots(
      TableViewUtils
        .takeEveryNth(timeIntervalMinutes)(crunchResultWithTimeAndInterval.recommendedDesks)
        .zip(millis).map {
        case (deskRec, timeInMillis) => DeskRecTimeslot(timeInMillis = timeInMillis, deskRec = deskRec)
      }.toList)
    updatedDeskRecTimeSlots
  }

  def queueRecsToDeployments(round: Double => Int)(queueRecs: List[(Int, String)], staffAvailable: Int, minMaxDesks: Map[QueueName, (Int, Int)]): Seq[Int] = {
    val totalStaffRec = queueRecs.map(_._1).sum

    queueRecs.foldLeft(List[Int]()) {
      case (agg, (deskRec, queue)) if agg.length < queueRecs.length - 1 =>
        val ideal = round(staffAvailable * (deskRec.toDouble / totalStaffRec))
        agg :+ deploymentWithinBounds(minMaxDesks(queue)._1, minMaxDesks(queue)._2, ideal, staffAvailable - agg.sum)
      case (agg, (_, queue)) =>
        val ideal = staffAvailable - agg.sum
        agg :+ deploymentWithinBounds(minMaxDesks(queue)._1, minMaxDesks(queue)._2, ideal, staffAvailable - agg.sum)
    }
  }
}

class AirportCountryHandler[M](timeProvider: () => Long, modelRW: ModelRW[M, Map[String, Pot[AirportInfo]]]) extends LoggingActionHandler(modelRW) {
  def mkPending = Pending(timeProvider())

  override def handle = {
    case GetAirportInfos(codes) =>
      val stringToObject: Map[String, Pot[AirportInfo]] = value ++ Map("BHX" -> mkPending, "EDI" -> mkPending)
      updated(stringToObject, Effect(AjaxClient[Api].airportInfosByAirportCodes(codes).call().map(UpdateAirportInfos)))
    case UpdateAirportInfos(infos) =>
      val infosReady = infos.map(kv => (kv._1, Ready(kv._2)))
      updated(value ++ infosReady)
    case GetAirportInfo(code) =>
      value.get(code) match {
        case None =>
          val stringToObject = value + (code -> Empty)
          updated(stringToObject, Effect(AjaxClient[Api].airportInfoByAirportCode(code).call().map(res => UpdateAirportInfo(code, res))))
        case Some(v) =>
          noChange
      }
    case UpdateAirportInfo(code, Some(airportInfo)) =>
      val newValue = value + (code -> Ready(airportInfo))
      updated(newValue)
  }
}

class ShiftsHandler[M](modelRW: ModelRW[M, Pot[String]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case SetShifts(shifts: String) =>
      updated(Ready(shifts), Effect(Future(RunAllSimulations())))
    case SaveShifts(shifts: String) =>
      AjaxClient[Api].saveShifts(shifts).call()
      effectOnly(Effect(Future(SetShifts(shifts))))
    case AddShift(shift) =>
      updated(Ready(s"${value.getOrElse("")}\n${shift.toCsv}"))
    case GetShifts() =>
      val shiftsEffect = Effect(Future(GetShifts())).after(300 seconds)
      val apiCallEffect = Effect(AjaxClient[Api].getShifts().call().map(res => SetShifts(res)))
      effectOnly(apiCallEffect + shiftsEffect)
  }
}

object FixedPoints {
  def filterTerminal(terminalName: TerminalName, rawFixedPoints: String): String = {
    rawFixedPoints.split("\n").toList.filter(line => {
      val terminal = line.split(",").toList.map(_.trim) match {
        case _ :: t :: _ => t
        case _ => Nil
      }
      terminal == terminalName
    }).mkString("\n")
  }

  def filterOtherTerminals(terminalName: TerminalName, rawFixedPoints: String): String = {
    rawFixedPoints.split("\n").toList.filter(line => {
      val terminal = line.split(",").toList.map(_.trim) match {
        case _ :: t :: _ => t
        case _ => Nil
      }
      terminal != terminalName
    }).mkString("\n")
  }

  def removeTerminalNameAndDate(rawFixedPoints: String) = {
    val lines = rawFixedPoints.split("\n").toList.map(line => {
      val withTerminal = line.split(",").toList.map(_.trim)
      val withOutTerminal = withTerminal match {
        case fpName :: terminal :: date :: tail => fpName.toString :: tail
        case _ => Nil
      }
      withOutTerminal.mkString(", ")
    })
    lines.mkString("\n")
  }

  def addTerminalNameAndDate(rawFixedPoints: String, terminalName: String) = {
    val today: SDateLike = SDate.today
    val todayString = today.ddMMyyString

    val lines = rawFixedPoints.split("\n").toList.map(line => {
      val withoutTerminal = line.split(",").toList.map(_.trim)
      val withTerminal = withoutTerminal match {
        case fpName :: tail => fpName.toString :: terminalName :: todayString :: tail
        case _ => Nil
      }
      withTerminal.mkString(", ")
    })
    lines.mkString("\n")
  }
}

class FixedPointsHandler[M](modelRW: ModelRW[M, Pot[String]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case SetFixedPoints(fixedPoints: String, terminalName: Option[String]) =>
      if (terminalName.isDefined)
        updated(Ready(fixedPoints), Effect(Future(RunTerminalSimulation(terminalName.get))))
      else
        updated(Ready(fixedPoints), Effect(Future(RunAllSimulations())))
    case SaveFixedPoints(fixedPoints: String, terminalName: TerminalName) =>
      val otherTerminalFixedPoints = FixedPoints.filterOtherTerminals(terminalName, value.getOrElse(""))
      val newRawFixedPoints = otherTerminalFixedPoints + "\n" + fixedPoints
      AjaxClient[Api].saveFixedPoints(newRawFixedPoints).call()
      effectOnly(Effect(Future(SetFixedPoints(newRawFixedPoints, Option(terminalName)))))
    case AddShift(fixedPoints) =>
      updated(Ready(s"${value.getOrElse("")}\n${fixedPoints.toCsv}"))
    case GetFixedPoints() =>
      val fixedPointsEffect = Effect(Future(GetFixedPoints())).after(60 minutes)
      val apiCallEffect = Effect(AjaxClient[Api].getFixedPoints().call().map(res => SetFixedPoints(res, None)))
      effectOnly(apiCallEffect + fixedPointsEffect)
  }
}

class StaffMovementsHandler[M](modelRW: ModelRW[M, Pot[Seq[StaffMovement]]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case AddStaffMovement(staffMovement) =>
      if (value.isReady) {
        val v: Seq[StaffMovement] = value.get
        val updatedValue: Seq[StaffMovement] = (v :+ staffMovement).sortBy(_.time)
        updated(Ready(updatedValue))
      } else noChange
    case RemoveStaffMovement(idx, uUID) =>
      if (value.isReady) {
        val terminalAffectedOption = value.get.find(_.uUID == uUID).map(_.terminalName)
        if (terminalAffectedOption.isDefined) {
          val updatedValue = value.get.filter(_.uUID != uUID)
          updated(Ready(updatedValue), Effect(Future(SaveStaffMovements(terminalAffectedOption.get))))
        } else noChange
      } else noChange
    case SetStaffMovements(staffMovements: Seq[StaffMovement]) =>
      updated(Ready(staffMovements), Effect(Future(RunAllSimulations())))
    case GetStaffMovements() =>
      val movementsEffect = Effect(Future(GetStaffMovements())).after(60 seconds)
      val apiCallEffect = Effect(AjaxClient[Api].getStaffMovements().call().map(res => SetStaffMovements(res)))
      effectOnly(apiCallEffect + movementsEffect)
    case SaveStaffMovements(terminalName) =>
      if (value.isReady) {
        AjaxClient[Api].saveStaffMovements(value.get).call()
        effectOnly(Effect(Future(RunTerminalSimulation(terminalName))))
      } else noChange
  }
}

class ActualDesksHandler[M](modelRW: ModelRW[M, Map[TerminalName, Map[QueueName, Map[Long, DeskStat]]]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetActualDeskStats() =>
      val nextRequest = Effect(Future(GetActualDeskStats())).after(60 seconds)
      val response = Effect(AjaxClient[Api].getActualDeskStats().call().map {
        case ActualDeskStats(deskStats) => SetActualDeskStats(deskStats)
      })
      effectOnly(response + nextRequest)
    case SetActualDeskStats(deskStats) =>
      updated(deskStats)
  }
}

class PointInTimeHandler[M](modelRW: ModelRW[M, Option[SDateLike]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetPointInTime(pointInTime) =>
      log.info(s"Set client point in time: $pointInTime")
      val sdatePointInTime = SDate.parse(pointInTime)
      val nextRequest = Effect(Future(RequestFlights()))
      updated(Option(sdatePointInTime), nextRequest)
  }
}

trait DrtCircuit extends Circuit[RootModel] with ReactConnector[RootModel] {
  val blockWidth = 15

  def timeProvider() = new Date().getTime.toLong

  override protected def initialModel = RootModel()

  def gotAllQueueCrunches() = {
    val queueCrunchResults = zoom(_.queueCrunchResults)

    val queueCrunchCount = queueCrunchResults.value.foldLeft(0)((acc, tq) => acc + tq._2.size)

    queueCrunchCount == totalQueues.values.sum
  }

  def totalQueues(): Map[TerminalName, Int] = {
    val airportConfig = zoom(_.airportConfig)
    if (airportConfig.value.isReady) {
      airportConfig.value.get.queues.mapValues(queues => queues.count(_ != Queues.Transfer))
    } else Map[TerminalName, Int]()
  }

  override val actionHandler = {
    val composedhandlers: HandlerFunction = composeHandlers(
      new WorkloadsHandler(zoomRW(_.workloadPot)((m, v) => {
        m.copy(workloadPot = v)
      })),
      new CrunchHandler(totalQueues, zoomRW(m => m.queueCrunchResults)((m, v) => m.copy(queueCrunchResults = v)), zoom(_.staffDeploymentsByTerminalAndQueue), zoom(_.airportConfig)),
      new SimulationHandler(zoom(_.airportConfig), gotAllQueueCrunches, zoom(_.shiftsRaw), zoom(_.fixedPointsRaw), zoom(_.staffMovements), zoom(_.staffDeploymentsByTerminalAndQueue), zoom(_.workloadPot), zoomRW(_.simulationResult)((m, v) => m.copy(simulationResult = v))),
      new FlightsHandler(zoom(_.pointInTime), zoomRW(_.flightsWithSplitsPot)((m, v) => m.copy(flightsWithSplitsPot = v))),
      new AirportCountryHandler(timeProvider, zoomRW(_.airportInfos)((m, v) => m.copy(airportInfos = v))),
      new AirportConfigHandler(zoomRW(_.airportConfig)((m, v) => m.copy(airportConfig = v))),
      new ShiftsHandler(zoomRW(_.shiftsRaw)((m, v) => m.copy(shiftsRaw = v))),
      new FixedPointsHandler(zoomRW(_.fixedPointsRaw)((m, v) => m.copy(fixedPointsRaw = v))),
      new StaffMovementsHandler(zoomRW(_.staffMovements)((m, v) => m.copy(staffMovements = v))),
      new ActualDesksHandler(zoomRW(_.actualDeskStats)((m, v) => m.copy(actualDeskStats = v))),
      new PointInTimeHandler(zoomRW(_.pointInTime)((m,v) => m.copy(pointInTime = v)))
    )

    val loggedhandlers: HandlerFunction = (model, update) => {
      log.debug(s"functional handler for ${update.toString.take(100)}")
      composedhandlers(model, update)
    }

    loggedhandlers

  }

  def pointInTimeMillis: Long = {
    zoom(_.pointInTime).value.map(_.millisSinceEpoch).getOrElse(0L)
  }
}

object SPACircuit extends DrtCircuit

case class GetAirportInfos(code: Set[String]) extends Action

case class GetAirportInfo(code: String) extends Action

case class UpdateAirportInfo(code: String, info: Option[AirportInfo]) extends Action

case class UpdateAirportInfos(infos: Map[String, AirportInfo]) extends Action

case class RunTerminalSimulation(terminalName: TerminalName) extends Action

case class UpdateTerminalSimulation(terminalName: TerminalName, terminalSimulationResultsFull: TerminalSimulationResultsFull) extends Action

case class GetTerminalCrunch(terminalName: TerminalName) extends Action

case class UpdateTerminalCrunchResult(terminalName: TerminalName, terminalCrunchResults: Map[QueueName, CrunchResult]) extends Action

case class RequestFlightsAfter(delaySeconds: Int) extends Action

case class UpdateFlightsAndContinuePolling(flights: FlightsWithSplits) extends Action

