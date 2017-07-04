package drt.client.services


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
                      queueCrunchResults: RootModel.TerminalQueueCrunchResults = Map(),
                      simulationResult: RootModel.TerminalQueueSimulationResults = Map(),
                      flightsWithSplitsPot: Pot[FlightsWithSplits] = Empty,
                      airportInfos: Map[String, Pot[AirportInfo]] = Map(),
                      airportConfig: Pot[AirportConfig] = Empty,
                      minutesInASlot: Int = 15,
                      shiftsRaw: Pot[String] = Empty,
                      fixedPointsRaw: Pot[String] = Empty,
                      staffMovements: Seq[StaffMovement] = Seq(),
                      slotsInADay: Int = 96,
                      flightSplits: Map[FlightCode, Map[MilliDate, VoyagePaxSplits]] = Map(),
                      actualDesks: Map[TerminalName, Map[QueueName, Map[Long, Option[Int]]]] = Map()
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

    val shifts = StaffAssignmentParser(rawShiftsString).parsedAssignments.toList
    val fixedPoints = StaffAssignmentParser(rawFixedPointsString).parsedAssignments.toList
    //todo we have essentially this code elsewhere, look for successfulShifts
    val staffFromShiftsAndMovementsAt = if (shifts.exists(s => s.isFailure) || fixedPoints.exists(s => s.isFailure)) {
      (t: TerminalName, m: MilliDate) => 0
    } else {
      val successfulShifts = shifts.collect { case Success(s) => s }
      val ss = StaffAssignmentService(successfulShifts)

      val successfulFixedPoints = fixedPoints.collect { case Success(s) => s }
      val fps = StaffAssignmentService(successfulFixedPoints)
      StaffMovements.terminalStaffAt(ss, fps)(staffMovements) _
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

  lazy val calculatedDeploymentRows: Pot[Map[TerminalName, Pot[List[TerminalDeploymentsRow]]]] = {
    airportConfig.map(ac => ac.terminalNames.map(terminalName => {
      calculateTerminalDeploymentRows(terminalName)
    }).toMap)
  }

  def calculateTerminalDeploymentRows(terminalName: TerminalName): (TerminalName, Pot[List[TerminalDeploymentsRow]]) = {
    val crv = queueCrunchResults.getOrElse(terminalName, Map())
    val srv = simulationResult.getOrElse(terminalName, Map())
    val udr = staffDeploymentsByTerminalAndQueue.getOrElse(terminalName, Map())
    val terminalDeploymentRows: Pot[List[TerminalDeploymentsRow]] = workloadPot.map(workloads => {
      workloads.workloads.get(terminalName) match {
        case Some(terminalWorkloads) =>
          val tried: Try[List[TerminalDeploymentsRow]] = Try {
            val timestamps = workloads.timeStamps()
            val startFromMilli = WorkloadsHelpers.midnightBeforeNow()
            val minutesRangeInMillis: NumericRange[Long] = WorkloadsHelpers.minutesForPeriod(startFromMilli, 24)

            val paxLoad: Map[String, List[Double]] = WorkloadsHelpers.paxloadPeriodByQueue(terminalWorkloads, minutesRangeInMillis)
            val actDesksForTerminal = actualDesks.getOrElse(terminalName, Map())

            TableViewUtils.terminalDeploymentsRows(terminalName, airportConfig, timestamps, paxLoad, crv, srv, udr, actDesksForTerminal)
          } recover {
            case f =>
              val terminalWorkloadsPprint = pprint.stringify(terminalWorkloads).take(1024)
              log.error(s"calculateTerminalDeploymentRows $f terminalWorkloads were: $terminalWorkloadsPprint", f.asInstanceOf[Exception])
              Nil
          }
          tried.get
        case None =>
          Nil
      }
    })
    terminalName -> terminalDeploymentRows
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
       |flightPaxSplits: $flightSplits
       |)
     """.stripMargin
}

object RootModel {

  type TerminalQueueSimulationResults = Map[TerminalName, Map[QueueName, Pot[SimulationResult]]]
  type TerminalQueueCrunchResults = Map[TerminalName, Map[QueueName, Pot[PotCrunchResult]]]

  type FlightCode = String

  type QueueCrunchResults = Map[QueueName, Pot[PotCrunchResult]]

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
        log.info("add new")
        DeskRecTimeSlots(items :+ newItem)
      case idx =>
        log.info(s"add old: idx: $idx, newItem: $newItem, ${items(idx)}")
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
    case udrt@UpdateDeskRecsTime(terminalName, queueName, deskRecTimeSlot) =>
      log.info(s"updating $udrt")
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
        log.error(s"Exception from ${getClass}  ${f.getMessage()} while handling $action")
        val cause = f.getCause()
        cause match {
          case null => log.error(s"no cause")
          case c => log.error(s"Exception from ${getClass}  ${c.getMessage()}")
        }

        throw f
      case Success(s) =>
        s
    }
  }
}

class AirportConfigHandler[M](modelRW: ModelRW[M, Pot[AirportConfig]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case action: GetAirportConfig =>
      log.info("Requesting AirportConfig from server")
      updated(Pending(), Effect(AjaxClient[Api].airportConfiguration().call().map(UpdateAirportConfig)))
    case UpdateAirportConfig(configHolder) =>
      log.info(s"Received AirportConfig $configHolder")
      log.info("Subscribing to crunches for terminal/queues")
      val effects: Effect = createCrunchRequestEffects(configHolder)
      updated(Ready(configHolder), effects)
  }

  def createCrunchRequestEffects(configHolder: AirportConfig): Effect = {
    val crunchRequests: Seq[Effect] = for {
      tn <- configHolder.terminalNames
      qn <- configHolder.queues(tn)
    } yield {
      Effect(Future(GetLatestCrunch(tn, qn)))
    }

    val effects = seqOfEffectsToEffectSeq(crunchRequests.toList)
    effects
  }
}

class WorkloadHandler[M](modelRW: ModelRW[M, Pot[Workloads]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case action: GetWorkloads =>
      log.info("requesting workloadsWrapper from server")
      updated(Pending(),
        Effect(AjaxClient[Api].getWorkloads().call().map(UpdateWorkloads).recover {
          case f =>
            log.error(s"failure getting workloads $f")
            NoAction
        }))
    case UpdateWorkloads(terminalQueueWorkloads: TerminalQueuePaxAndWorkLoads[(Seq[WL], Seq[Pax])]) =>
      val terminalQueues = terminalQueueWorkloads.flatMap {
        case (tn, qn2wl) =>
          qn2wl.keys.map(qn => (tn, qn))
      }

      log.info(s"received tqwl $terminalQueues")

      val paxLoadsByTerminalAndQueue: Map[TerminalName, Map[QueueName, Seq[Pax]]] = terminalQueueWorkloads.mapValues(_.mapValues(_._2))
      for {(t, tl) <- paxLoadsByTerminalAndQueue
           loadAtTerminal = tl.map(_._2.map(_.pax).sum)
           (q, ql) <- tl
           loadAtQueue = ql.map(_.pax).sum
           firstTime = ql.headOption.map(_.time)
           lastTimeOpt = ql.lastOption.map(_.time)
      } {
        val firstTimeSDate = firstTime.map(d => SDate(MilliDate(d)))
        val lastTime = lastTimeOpt.map(d => SDate(MilliDate(d)))
        log.debug(s"received workloads: paxLoads:  firstTime: $firstTime (${firstTimeSDate}) lastTime $lastTime $t/$q $loadAtQueue / $loadAtTerminal")
      }

      val roundedTimesToMinutes: Map[TerminalName, Map[QueueName, (Seq[WL], Seq[Pax])]] = terminalQueueWorkloads.mapValues(q => q.mapValues(plWl =>
        (plWl._1.map(pl => WL(timeFromMillisToNearestSecond(pl.time), pl.workload)),
          plWl._2.map(pl => Pax(timeFromMillisToNearestSecond(pl.time), pl.pax)))))

      updated(Ready(Workloads(roundedTimesToMinutes)))
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

class SimulationHandler[M](staffDeployments: ModelR[M, TerminalQueueStaffDeployments],
                           modelR: ModelR[M, Pot[Workloads]],
                           modelRW: ModelRW[M, Map[TerminalName, Map[QueueName, Pot[SimulationResult]]]])
  extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case RunAllSimulations() =>
      log.info(s"run simulation for ${staffDeployments.value}")
      val actions = staffDeployments.value.flatMap {
        case (terminalName, queueMapPot) => {
          queueMapPot.map {
            case (queueName, deployedDesks) =>
              //              val queueWorkload = getTerminalQueueWorkload(terminalName, queueName)
              val desks = deployedDesks.get.items.map(_.deskRec)
              val desksList = desks.toList
              Effect(Future(RunSimulation(terminalName, queueName, desksList)))
          }
        }
      }.toList
      log.info(s"runAllSimulations effects ${actions}")
      effectOnly(seqOfEffectsToEffectSeq(actions))
    case rs@RunSimulation(terminalName, queueName, desks) =>
      log.info(s"Requesting simulation for $terminalName, $queueName")
      val queueWorkload = getTerminalQueueWorkload(terminalName, queueName)
      val firstNonZeroIndex = queueWorkload.indexWhere(_ != 0)
      log.info(s"run sim first != 0 workload $firstNonZeroIndex")
      log.info(s"run sum $rs workload $queueWorkload")
      val simulationResult: Future[SimulationResult] = AjaxClient[Api].processWork(terminalName, queueName, queueWorkload, desks).call()
      effectOnly(
        Effect(simulationResult.map(resp => UpdateSimulationResult(terminalName, queueName, resp)))
      )
    case sr@UpdateSimulationResult(terminalName, queueName, simResult) =>
      val firstNonZeroIndex = simResult.waitTimes.indexWhere(_ != 0)
      log.info(s"run sim simResult $sr")
      log.info(s"$terminalName/$queueName updateSimResult ${firstNonZeroIndex}")
      updated(mergeTerminalQueues(value, Map(terminalName -> Map(queueName -> Ready(simResult)))))
  }

  private def getTerminalQueueWorkload(terminalName: TerminalName, queueName: QueueName): List[Double] = {
    val startFromMilli = WorkloadsHelpers.midnightBeforeNow()
    log.info(s"startFromMilli: $startFromMilli")
    val minutesRangeInMillis: NumericRange[Long] = WorkloadsHelpers.minutesForPeriod(startFromMilli, 24)
    val workloadPot = modelR.value
    workloadPot match {
      case Ready(workload) =>
        workload.workloads.get(terminalName) match {
          case Some(terminalWorkload) =>
            val queueWorkload: List[Double] = WorkloadsHelpers.workloadPeriodByQueue(terminalWorkload, minutesRangeInMillis)(queueName)
            queueWorkload
          case None => Nil
        }
      case _ => Nil
    }

  }
}

case class GetLatestCrunch(terminalName: TerminalName, queueName: QueueName) extends Action

case class RequestFlights(from: Long, to: Long) extends Action

case class UpdateFlights(flights: Flights) extends Action

case class UpdateFlightsWithSplits(flights: FlightsWithSplits) extends Action

case class UpdateFlightPaxSplits(splitsEither: Either[FlightNotFound, VoyagePaxSplits]) extends Action

class FlightsHandler[M](modelRW: ModelRW[M, Pot[FlightsWithSplits]]) extends LoggingActionHandler(modelRW) {
  val flightsRequestFrequency = 10L seconds

  protected def handle = {
    case RequestFlights(from, to) =>
      val flightsEffect = Effect(Future(RequestFlights(0, 0))).after(flightsRequestFrequency)
      val fe = Effect(AjaxClient[Api].flightsWithSplits(from, to).call().map(UpdateFlightsWithSplits(_)))
      effectOnly(fe + flightsEffect)
    case UpdateFlightsWithSplits(flightsWithSplits) =>
      log.info(s"client got ${flightsWithSplits.flights.length} flights")
      val flights = flightsWithSplits.flights.map(_.apiFlight)

      val result = if (value.isReady) {
        val oldFlights = value.get
        val oldFlightsSet = oldFlights.flights.toSet
        val newFlightsSet = flights.toSet
        if (oldFlightsSet != newFlightsSet) {
          val airportCodes = flights.map(_.Origin).toSet
          val airportInfos = Effect(Future(GetAirportInfos(airportCodes)))

          val getWorkloads = {
            //todo - our heatmap updated too frequently right now if we do this, will require some shouldComponentUpdate finesse
            Effect(Future {
              log.info("flights Have changed - re-requesting workloads")
              GetWorkloads("", "")
            })
          }

          val allEffects = airportInfos //>> getWorkloads
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
          log.debug(s"$terminalName/$queueName Failed to fetch crunch - has a crunch run yet? $ncr")
          NoAction
      }
      effectOnly(Effect(fe) + crunchEffect)
    case UpdateCrunchResult(terminalName, queueName, crunchResultWithTimeAndInterval) =>
      log.info(s"UpdateCrunchResult $queueName. firstTimeMillis: ${crunchResultWithTimeAndInterval.firstTimeMillis}")
      val firstNonZeroIndex = crunchResultWithTimeAndInterval.waitTimes.indexWhere(_ != 0)
      log.info(s"$terminalName/$queueName crunchResult: $firstNonZeroIndex")
      val crunchResultsByQueue = mergeTerminalQueues(value, Map(terminalName -> Map(queueName -> Ready(Ready(crunchResultWithTimeAndInterval)))))
      if (modelQueueCrunchResults != crunchResultsByQueue) updated(crunchResultsByQueue)
      else noChange
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
    val crunchResultWithTimeAndIntervalTry = Try(terminalQueueCrunchResults(firstTerminalName).head._2.get.get)

    crunchResultWithTimeAndIntervalTry.map(crunchResultWithTimeAndInterval => {

      val drts: DeskRecTimeSlots = calculateDeskRecTimeSlots(crunchResultWithTimeAndInterval)

      val newSuggestedStaffDeployments: Map[TerminalName, Map[QueueName, Ready[DeskRecTimeSlots]]] = terminalQueueCrunchResults.map((terminalQueueCrunchResult: (TerminalName, QueueCrunchResults)) => {
        val terminalName: TerminalName = terminalQueueCrunchResult._1
        val terminalStaffAvailable = staffAvailable(terminalName)
        val queueCrunchResult = terminalQueueCrunchResult._2

        /*
         Fixme: This transpose loses the queue name and thus certainty of order
         */
        val queueDeskRecsOverTime: Iterable[Iterable[DeskRecTimeslot]] = queueCrunchResult.transpose {
          case (_, Ready(Ready(cr))) => calculateDeskRecTimeSlots(cr).items
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
      case (agg, (deskRec, queue)) =>
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
      val newValue = value + (code -> Ready(airportInfo))
      log.info(s"got a new value for $code $airportInfo")
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

class FixedPointsHandler[M](modelRW: ModelRW[M, Pot[String]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case SetFixedPoints(fixedPoints: String) =>
      updated(Ready(fixedPoints), Effect(Future(RunAllSimulations())))
    case SaveFixedPoints(fixedPoints: String) =>
      AjaxClient[Api].saveFixedPoints(fixedPoints).call()
      noChange
    case AddShift(fixedPoints) =>
      updated(Ready(s"${value.getOrElse("")}\n${fixedPoints.toCsv}"))
    case GetFixedPoints() =>
      effectOnly(Effect(AjaxClient[Api].getFixedPoints().call().map(res => SetFixedPoints(res))))
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

class ActualDesksHandler[M](modelRW: ModelRW[M, Map[TerminalName, Map[QueueName, Map[Long, Option[Int]]]]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetActualDesks() =>
      val nextRequest = Effect(Future(GetActualDesks())).after(15 seconds)
      val response = Effect(AjaxClient[Api].getActualDesks().call().map {
        case ActualDesks(desks) =>
          log.info(s"Received ActualDesks($desks) from Api")
          SetActualDesks(desks)
      })
      effectOnly(response + nextRequest)
    case SetActualDesks(desks) =>
      log.info(s"SetActualDesks($desks)")
      updated(desks)
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
    val composedhandlers: HandlerFunction = composeHandlers(
      new WorkloadHandler(zoomRW(_.workloadPot)((m, v) => {
        m.copy(workloadPot = v)
      })),
      new CrunchHandler(zoomRW(m => m.queueCrunchResults)((m, v) => m.copy(queueCrunchResults = v))),
      new SimulationHandler(zoom(_.staffDeploymentsByTerminalAndQueue), zoom(_.workloadPot), zoomRW(_.simulationResult)((m, v) => m.copy(simulationResult = v))),
      new FlightsHandler(zoomRW(_.flightsWithSplitsPot)((m, v) => m.copy(flightsWithSplitsPot = v))),
      new AirportCountryHandler(timeProvider, zoomRW(_.airportInfos)((m, v) => m.copy(airportInfos = v))),
      new AirportConfigHandler(zoomRW(_.airportConfig)((m, v) => m.copy(airportConfig = v))),
      new ShiftsHandler(zoomRW(_.shiftsRaw)((m, v) => m.copy(shiftsRaw = v))),
      new FixedPointsHandler(zoomRW(_.fixedPointsRaw)((m, v) => m.copy(fixedPointsRaw = v))),
      new StaffMovementsHandler(zoomRW(_.staffMovements)((m, v) => m.copy(staffMovements = v))),
      new ActualDesksHandler(zoomRW(_.actualDesks)((m, v) => m.copy(actualDesks = v)))
    )

    val loggedhandlers: HandlerFunction = (model, update) => {
      log.debug(s"functional handler for ${update.toString.take(100)}")
      composedhandlers(model, update)
    }

    loggedhandlers

  }
}

object SPACircuit extends DrtCircuit

case class GetAirportInfos(code: Set[String]) extends Action

case class GetAirportInfo(code: String) extends Action

case class UpdateAirportInfo(code: String, info: Option[AirportInfo]) extends Action

case class UpdateAirportInfos(infos: Map[String, AirportInfo]) extends Action

