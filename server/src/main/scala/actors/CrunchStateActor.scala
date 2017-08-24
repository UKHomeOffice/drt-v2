package actors

import akka.actor._
import akka.persistence.{PersistentActor, RecoveryCompleted, SaveSnapshotSuccess, SnapshotOffer}
import controllers.GetTerminalCrunch
import drt.shared.FlightsApi._
import drt.shared._
import server.protobuf.messages.CrunchState._
import server.protobuf.messages.CrunchStateDiff.{CrunchDiffMessage, CrunchStateDiffMessage, QueueLoadDiffMessage}
import services.Crunch._
import services._
import services.workloadcalculator.PaxLoadCalculator.MillisSinceEpoch

import scala.collection.immutable._
import scala.language.postfixOps
import scala.util.{Success, Try}


class CrunchStateActor(portQueues: Map[TerminalName, Seq[QueueName]]) extends PersistentActor with ActorLogging {
  override def persistenceId: String = "crunch-state"

  var state: Option[CrunchState] = None

  val snapshotInterval = 25

  override def receiveRecover: Receive = {
    case SnapshotOffer(metadata, snapshot) =>
      log.info(s"restoring crunch state")
      snapshot match {
        case sm@CrunchStateSnapshotMessage(_, _, _, _) =>
          log.info(s"matched CrunchStateSnapshotMessage $metadata, storing it.")
          state = Option(snapshotMessageToState(sm))
        case somethingElse =>
          log.info(s"Got $somethingElse when trying to restore Crunch State")
      }
    case csdm@CrunchStateDiffMessage(start, fd, qd, cd, _) =>
      log.info(s"recovery: received CrunchStateDiff - $start, ${fd.size} flights, ${qd.size} queue minutes, ${cd.size} crunch minutes")
      updateStateFromDiff(crunchStateDiffFromMessage(csdm))
  }

  override def receiveCommand: Receive = {
    case SaveSnapshotSuccess =>
      log.info("saved CrunchState Snapshot")
    case csd@CrunchStateDiff(start, fd, qd, cd) =>
      log.info(s"received CrunchStateDiff - $start, ${fd.size} flights, ${qd.size} queue minutes, ${cd.size} crunch minutes")
      updateStateFromDiff(csd)
      persistDiff(csd)
    case GetFlights =>
      state match {
        case Some(CrunchState(flights, _, _, _)) =>
          sender() ! FlightsWithSplits(flights)
        case None => FlightsNotReady
      }
    case GetPortWorkload =>
      state match {
        case Some(CrunchState(_, workloads, _, _)) =>
          val portWorkloadByTerminalAndQueue = workloads.mapValues {
            case twl => twl.mapValues {
              case qwl =>
                val wl = qwl.map(wlm => WL(wlm._1, wlm._2._2))
                val pl = qwl.map(wlm => Pax(wlm._1, wlm._2._1))
                log.info(s"GetPortWorkload, wl: ${wl.length}, pl: ${pl.length}")
                (wl, pl)
            }
          }
          sender() ! portWorkloadByTerminalAndQueue
        case None => WorkloadsNotReady
      }
    case GetTerminalCrunch(terminalName) =>
      val terminalCrunchResults: List[(QueueName, Either[NoCrunchAvailable, CrunchResult])] = state match {
        case Some(CrunchState(_, _, portCrunchResult, crunchFirstMinuteMillis)) =>
          portCrunchResult.getOrElse(terminalName, Map()).map {
            case (queueName, optimiserCRTry) =>
              optimiserCRTry match {
                case Success(OptimizerCrunchResult(deskRecs, waitTimes)) =>
                  (queueName, Right(CrunchResult(crunchFirstMinuteMillis, 60000, deskRecs, waitTimes)))
                case _ =>
                  (queueName, Left(NoCrunchAvailable()))
              }
          }.toList
        case None => List[(QueueName, Either[NoCrunchAvailable, CrunchResult])]()
      }
      sender() ! terminalCrunchResults
    case flightsSubscriber: ActorRef =>
      log.info(s"received flightsSubscriber. sending flights")
      val flightsForInitialisation = state match {
        case None =>
          log.info(s"No state recovered so sending blank CrunchFlights")
          CrunchFlights(flights = List(), crunchStart = 0L, crunchEnd = 0L + oneDay, initialState = true)
        case Some(cs) =>
          val crunchEnd = cs.crunchFirstMinuteMillis + (1439 * oneMinute)
          CrunchFlights(flights = cs.flights, crunchStart = cs.crunchFirstMinuteMillis, crunchEnd = crunchEnd, initialState = true)
      }
      log.info(s"sending ${flightsForInitialisation.flights.length} flights")
      flightsSubscriber ! flightsForInitialisation

    case RecoveryCompleted =>
      log.info("Finished restoring crunch state")
  }

  def emptyWorkloads(firstMinuteMillis: MillisSinceEpoch): Map[TerminalName, Map[QueueName, List[(Long, (Double, Double))]]] = {
    portQueues.map {
      case (tn, q) =>
        val tl = q.filterNot(_ == Queues.Transfer).map(queueName => {
          val ql = oneDayOfMinutes.map(minute => {
            (firstMinuteMillis + (minute * oneMinute), (0d, 0d))
          }).toList
          (queueName, ql)
        }).toMap
        (tn, tl)
    }
  }

  def oneDayOfMinutes = {
    0 until 1440
  }

  def emptyCrunch(crunchStartMillis: MillisSinceEpoch) = portQueues
    .mapValues(_.filterNot(_ == Queues.Transfer)
      .map(queueName => {
        val zeros = oneDayOfMinutes.map(_ => 0).toList
        (queueName, Success(OptimizerCrunchResult(zeros.toIndexedSeq, zeros)))
      }).toMap)

  def emptyState(crunchStartMillis: MillisSinceEpoch) = {
    CrunchState(
      crunchFirstMinuteMillis = crunchStartMillis,
      flights = List(),
      workloads = emptyWorkloads(crunchStartMillis),
      crunchResult = emptyCrunch(crunchStartMillis)
    )
  }

  def crunchStateDiffFromMessage(csdm: CrunchStateDiffMessage) = {
    CrunchStateDiff(
      csdm.crunchFirstMinuteTimestamp.getOrElse(0L),
      csdm.flightDiffs.map(flightWithSplitsFromMessage).toSet,
      csdm.queueDiffs.map(queueLoadDiffFromMessage).toSet,
      csdm.crunchDiffs.map(crunchDiffFromMessage).toSet
    )
  }

  def crunchDiffFromMessage(cdm: CrunchDiffMessage) = {
    CrunchDiff(
      cdm.terminalName.getOrElse(""),
      cdm.queueName.getOrElse(""),
      cdm.minuteTimestamp.getOrElse(0L),
      cdm.desks.getOrElse(0),
      cdm.waitTime.getOrElse(0)
    )
  }

  def queueLoadDiffFromMessage(qdm: QueueLoadDiffMessage) = {
    QueueLoadDiff(
      qdm.terminalName.getOrElse(""),
      qdm.queueName.getOrElse(""),
      qdm.minuteTimestamp.getOrElse(0L),
      qdm.pax.getOrElse(0),
      qdm.work.getOrElse(0)
    )
  }

  def stateDiffToMessage(csd: CrunchStateDiff) = {
    CrunchStateDiffMessage(
      crunchFirstMinuteTimestamp = Option(csd.crunchFirstMinuteMillis),
      flightDiffs = csd.flightDiffs.map(FlightMessageConversion.flightWithSplitsToMessage).toSeq,
      queueDiffs = csd.queueDiffs.map(queueLoadDiffToMessage).toSeq
    )
  }

  def queueLoadDiffToMessage(qd: QueueLoadDiff) = {
    QueueLoadDiffMessage(
      Option(qd.terminalName),
      Option(qd.queueName),
      Option(qd.minute),
      Option(qd.paxLoad),
      Option(qd.workLoad)
    )
  }

  def persistDiff(csd: CrunchStateDiff) = {
    persist(csd) { (crunchDiff: CrunchStateDiff) =>
      val diffMessage = stateDiffToMessage(crunchDiff)
      log.info(s"persisting CrunchStateDiff")
      context.system.eventStream.publish(diffMessage)
      state.foreach(s =>
        if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
          log.info("saving CrunchState snapshot")
          saveSnapshot(stateToSnapshotMessage(s))
        })
    }
  }

  def updateStateFromDiff(csd: CrunchStateDiff) = {
    val currentState = state match {
      case Some(s) => s
      case None => emptyState(csd.crunchFirstMinuteMillis)
    }

    state = Option(currentState.copy(
      flights = updatedFlightState(csd.flightDiffs, currentState)
        .filter(fs => (fs.apiFlight.PcpTime + 30 * oneMinute) >= csd.crunchFirstMinuteMillis),
      workloads = updatedLoadState(csd.queueDiffs, currentState),
      crunchResult = updatedCrunchState(csd.crunchFirstMinuteMillis, csd.crunchDiffs, currentState)
    ))
  }

  def updatedCrunchState(crunchStartMillis: Long, crunches: Set[CrunchDiff], currentState: CrunchState) = {
    val crunchByTQM = crunchByTerminalQueueMinute(currentState)
    val updatedTQM = applyCrunchDiffsToTQM(crunches, crunchByTQM)
    val updatedCrunch = updatedTQM
      .groupBy { case ((tn, qn, m), l) => tn }
      .map {
        case ((tn, tl)) => (tn, terminalCrunchFromQueuesAndCrunch(tl))
      }
    updatedCrunch
  }

  def applyCrunchDiffsToTQM(crunches: Set[CrunchDiff], crunchByTQM: Map[(TerminalName, QueueName, MillisSinceEpoch), (Int, Int)]) = {
    val updatedTQM = crunches.foldLeft(crunchByTQM) {
      case (crunchesSoFar, CrunchDiff(tn, qn, m, dr, wt)) =>
        val currentCr = crunchesSoFar.getOrElse((tn, qn, m), (0, 0))
        crunchesSoFar.updated((tn, qn, m), (currentCr._1 + dr, currentCr._2 + wt))
    }
    updatedTQM
  }

  def crunchByTerminalQueueMinute(currentState: CrunchState) = {
    val startMillis = currentState.crunchFirstMinuteMillis
    val crunchByTQM: Map[(TerminalName, QueueName, MillisSinceEpoch), (Int, Int)] = currentState.crunchResult.flatMap {
      case (tn, tc) => tc.flatMap { case (qn, qc) => crunchResultByTQM(startMillis, tn, qn, qc) }
    }
    crunchByTQM
  }

  def crunchResultByTQM(startMillis: MillisSinceEpoch, tn: TerminalName, qn: QueueName, qc: Try[OptimizerCrunchResult]): IndexedSeq[((TerminalName, QueueName, MillisSinceEpoch), (Int, Int))] = {
    qc match {
      case Success(c) =>
        c.recommendedDesks.indices.map(m => {
          ((tn, qn, startMillis + (m * oneMinute)), (c.recommendedDesks(m), c.waitTimes(m)))
        })
    }
  }

  def terminalCrunchFromQueuesAndCrunch(tl: Map[(TerminalName, QueueName, MillisSinceEpoch), (Int, Int)]): Map[QueueName, Success[OptimizerCrunchResult]] = {
    tl
      .groupBy { case ((_, qn, _), _) => qn }
      .map {
        case (qn, ql) =>
          val deskRecs: IndexedSeq[Int] = deskRecsFromMillisAndDeskRecs(ql)
          val waitTimes: List[Int] = waitTimesFromMillisAndWaitTimes(ql)
          (qn, Success(OptimizerCrunchResult(deskRecs, waitTimes)))
      }
  }

  def waitTimesFromMillisAndWaitTimes(ql: Map[(TerminalName, QueueName, MillisSinceEpoch), (Int, Int)]) = {
    val waitTimes = ql.toSeq.map {
      case ((_, _, millis), (_, wt)) => (millis, wt)
    }.toList.sortBy(_._1).map(_._2)
    waitTimes
  }

  def deskRecsFromMillisAndDeskRecs(ql: Map[(TerminalName, QueueName, MillisSinceEpoch), (Int, Int)]) = {
    val deskRecs = ql.map {
      case ((_, _, millis), (dr, _)) => (millis, dr)
    }.toList.sortBy(_._1).map(_._2).toIndexedSeq
    deskRecs
  }

  def updatedLoadState(queueLoads: Set[QueueLoadDiff], currentState: CrunchState) = {
    val loadByTQM = loadByTerminalQueueMinute(currentState)
    val updatedTQM = applyQueueLoadDiffsToTQM(queueLoads, loadByTQM, currentState.crunchFirstMinuteMillis)
    val updatedLoads = updatedTQM
      .groupBy { case ((tn, qn, m), l) => tn }
      .map {
        case ((tn, tl)) =>
          (tn, terminalLoadFromQueuesAndLoads(tl))
      }
//    log.info(s"updatedLoads: $updatedLoads")
    updatedLoads
  }

  def applyQueueLoadDiffsToTQM(queueLoads: Set[QueueLoadDiff], loadByTQM: Map[(TerminalName, QueueName, MillisSinceEpoch), (Double, Double)], crunchStartMinute: MillisSinceEpoch): Map[(TerminalName, QueueName, MillisSinceEpoch), (Double, Double)] = {
    val updatedTQM = queueLoads.foldLeft(loadByTQM) {
      case (loadsSoFar, QueueLoadDiff(tn, qn, m, pl, wl)) =>
        if (m >= crunchStartMinute && m < crunchStartMinute + oneDay) {
          val currentLoads = loadsSoFar.getOrElse((tn, qn, m), (0d, 0d))
          loadsSoFar.updated((tn, qn, m), (currentLoads._1 + pl, currentLoads._2 + wl))
        } else loadsSoFar
    }
    updatedTQM
  }

  def loadByTerminalQueueMinute(currentState: CrunchState) = currentState.workloads.flatMap {
    case (tn, tlw) => tlw.flatMap {
      case (qn, qlw) => qlw.map {
        case (millis, (pl, wl)) => ((tn, qn, millis), (pl, wl))
      }
    }
  }

  def terminalLoadFromQueuesAndLoads(tl: Map[(TerminalName, QueueName, MillisSinceEpoch), (Double, Double)]) = tl.groupBy {
    case ((_, qn, _), _) => qn
  }.map {
    case (qn, ql) =>
      (qn, queueLoadsFromMillisAndLoads(ql))
  }

  def queueLoadsFromMillisAndLoads(ql: Map[(TerminalName, QueueName, MillisSinceEpoch), (Double, Double)]) = ql.map {
    case ((tn, qn, m), (pl, wl)) => (m, (pl, wl))
  }.toList

  def updatedFlightState(flights: Set[ApiFlightWithSplits], currentState: CrunchState) = {
    val flightsById = currentState.flights.map(f => (f.apiFlight.FlightID, f)).toMap
    val updatedFlights = flights.foldLeft(flightsById) {
      case (flightsSoFar, newFlight) => flightsSoFar.updated(newFlight.apiFlight.FlightID, newFlight)
    }.values.toList
    updatedFlights
  }

  def snapshotMessageToState(snapshot: CrunchStateSnapshotMessage) = CrunchState(
    snapshot.flightWithSplits.map(flightWithSplitsFromMessage).toList,
    snapshot.terminalLoad.map(tlm => (tlm.terminalName.get, terminalLoadFromMessage(tlm))).toMap,
    snapshot.terminalCrunch.map(tcm => (tcm.terminalName.get, terminalCrunchResultFromMessage(tcm))).toMap,
    snapshot.crunchFirstMinuteTimestamp.get
  )

  def terminalCrunchResultFromMessage(tcm: TerminalCrunchMessage) = {
    val toMap = tcm.queueCrunch.map(qcm => {
      val cm = qcm.crunch.get
      (qcm.queueName.get, Success(OptimizerCrunchResult(cm.desks.toIndexedSeq, cm.waitTimes.toList)))
    }).toMap
    toMap
  }

  def terminalLoadFromMessage(tlm: TerminalLoadMessage) = {
    val toMap = tlm.queueLoad.map(qlm => {
      (qlm.queueName.get, queueLoadMessagesToQueueLoad(qlm))
    }).toMap
    toMap
  }

  def queueLoadMessagesToQueueLoad(qlm: QueueLoadMessage) = {
    val toList = qlm.load.map(lm => {
      (lm.timestamp.get, (lm.pax.get, lm.work.get))
    }).toList
    toList
  }

  def flightWithSplitsFromMessage(fm: FlightWithSplitsMessage) = {
    ApiFlightWithSplits(
      FlightMessageConversion.flightMessageV2ToArrival(fm.flight.get),
      fm.splits.map(apiSplitsFromMessage).toList
    )
  }

  def apiSplitsFromMessage(sm: SplitMessage) = {
    ApiSplits(
      sm.paxTypeAndQueueCount.map(pqcm => {
        ApiPaxTypeAndQueueCount(PaxType(pqcm.paxType.get), pqcm.queueType.get, pqcm.paxValue.get)
      }).toList, sm.source.get, SplitStyle(sm.style.get))
  }

  def stateToSnapshotMessage(crunchState: CrunchState): CrunchStateSnapshotMessage = {
    CrunchStateSnapshotMessage(
      crunchState.flights.map(FlightMessageConversion.flightWithSplitsToMessage),
      terminalLoadsToMessages(crunchState.workloads),
      terminalCrunchesToMessages(crunchState.crunchResult),
      Option(crunchState.crunchFirstMinuteMillis)
    )
  }

  def terminalCrunchesToMessages(crunchResult: Map[TerminalName, Map[QueueName, Try[OptimizerCrunchResult]]]) = {
    crunchResult.map {
      case (terminalName, queueCrunch) =>
        terminalCrunchToMessage(terminalName, queueCrunch)
    }.toList
  }

  def terminalLoadsToMessages(loads: Map[TerminalName, Map[QueueName, List[(Long, (Double, Double))]]]): List[TerminalLoadMessage] = {
    loads.map {
      case (terminalName, queueLoads) =>
        terminalLoadToMessage(terminalName, queueLoads)
    }.toList
  }

  def terminalCrunchToMessage(terminalName: TerminalName, queueCrunch: Map[QueueName, Try[OptimizerCrunchResult]]) = {
    TerminalCrunchMessage(Option(terminalName), queueCrunch.collect {
      case (queueName, Success(cr)) =>
        QueueCrunchMessage(Option(queueName), Option(CrunchMessage(cr.recommendedDesks, cr.waitTimes)))
    }.toList)
  }

  def terminalLoadToMessage(terminalName: TerminalName, queueLoads: Map[QueueName, List[(MillisSinceEpoch, (Double, Double))]]) = {
    TerminalLoadMessage(Option(terminalName), queueLoads.map {
      case (queueName, loads) => queueLoadToMessage(queueName, loads)
    }.toList)
  }

  def queueLoadToMessage(queueName: QueueName, loads: List[(MillisSinceEpoch, (Double, Double))]) = {
    QueueLoadMessage(Option(queueName), loads.map {
      case (timestamp, (pax, work)) => LoadMessage(Option(timestamp), Option(pax), Option(work))
    })
  }
}

case object GetPortWorkload
