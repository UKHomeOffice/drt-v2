package actors

import actors.SplitsConversion.splitMessageToApiSplits
import akka.actor._
import akka.persistence._
import drt.shared.CrunchApi._
import drt.shared.FlightsApi._
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState._
import services.SDate
import services.graphstages.Crunch
import services.graphstages.Crunch._

import scala.collection.immutable
import scala.language.postfixOps
import scala.util.Success

class CrunchStateActor(val snapshotInterval: Int,
                       name: String,
                       portQueues: Map[TerminalName, Seq[QueueName]],
                       now: () => SDateLike,
                       expireAfterMillis: Long,
                       purgePreviousSnapshots: Boolean) extends PersistentActor {
  override def persistenceId: String = name

  val log: Logger = LoggerFactory.getLogger(s"$name-$getClass")

  def logInfo(msg: String): Unit = if (name.isEmpty) log.info(msg) else log.info(s"$name $msg")

  var state: Option[PortState] = None

  override def receiveRecover: Receive = {
    case SnapshotOffer(metadata, snapshot) =>
      logInfo(s"Recovery: received SnapshotOffer ${metadata.timestamp} with ${snapshot.getClass}")
      setStateFromSnapshot(snapshot)

    case cdm: CrunchDiffMessage =>
      logInfo(s"Recovery: received CrunchDiffMessage")
      val newState = stateFromDiff(cdm, state)
      logRecoveryState(newState)
      state = newState

    case RecoveryCompleted =>
      logInfo("Recovery: Finished restoring crunch state")

    case u =>
      logInfo(s"Recovery: received unexpected ${u.getClass}")
  }

  def logRecoveryState(optionalState: Option[PortState]): Unit = optionalState match {
    case None => logInfo(s"Recovery: state is None")
    case Some(s) =>
      val apiCount = s.flights.count {
        case (_, f) => f.splits.exists {
          case ApiSplits(_, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, _, _) => true
          case _ => false
        }
      }
      logInfo(s"Recovery: state contains ${s.flights.size} flights " +
        s"with $apiCount Api splits " +
        s", ${s.crunchMinutes.size} crunch minutes " +
        s", ${s.staffMinutes.size} staff minutes ")
  }

  override def receiveCommand: Receive = {
    case flightRemovals: FlightRemovals =>
      logInfo(s"Got ${flightRemovals.idsToRemove.size} flight removals")
      val updatedState = state match {
        case None => PortState(Map(), Map(), Map())
        case Some(portState) => updatePortState(flightRemovals, portState)
      }
      updateStateFromPortState(updatedState)

    case flightUpdates: FlightsWithSplits =>
      logInfo(s"Got ${flightUpdates.flights.size} updated flights")
      val updatedState = state match {
        case None => newPortState(flightUpdates)
        case Some(portState) => updatePortState(flightUpdates, portState)
      }
      updateStateFromPortState(updatedState)

    case drms: DeskRecMinutes =>
      logInfo(s"Got ${drms.minutes.size} updated desk rec minutes")
      val updatedState = state match {
        case None => newPortState(drms)
        case Some(portState) => updatePortState(drms, portState)
      }
      updateStateFromPortState(updatedState)

    case sims: SimulationMinutes =>
      logInfo(s"Got ${sims.minutes.size} updated simulation minutes")
      val updatedState = state match {
        case None => newPortState(sims)
        case Some(portState) => updatePortState(sims, portState)
      }
      updateStateFromPortState(updatedState)

    case sms: StaffMinutes =>
      logInfo(s"Got ${sms.minutes.size} updated staff minutes")
      val updatedState = state match {
        case None => newPortState(sms)
        case Some(portState) => updatePortState(sms, portState)
      }
      updateStateFromPortState(updatedState)

    case actDesks: ActualDeskStats =>
      state = state match {
        case None => None
        case Some(portState) => Option(updatePortState(actDesks, portState))
      }

    case cs: PortState =>
      logInfo(s"Received PortState. storing")
      updateStateFromPortState(cs)

    case GetState =>
      logInfo(s"Received GetState request. Replying with ${state.map(s => s"PortState containing ${s.crunchMinutes.size} crunch minutes")}")
      sender() ! state

    case GetPortState(start: MillisSinceEpoch, end: MillisSinceEpoch) =>
      sender() ! stateForPeriod(start, end)

    case GetUpdatesSince(millis, start, end) =>
      val updates = state match {
        case Some(cs) =>
          val updatedFlights = cs.flights.filter {
            case (_, f) => f.lastUpdated.getOrElse(1L) > millis && start <= f.apiFlight.PcpTime && f.apiFlight.PcpTime < end
          }.values.toSet
          val updatedCrunch = cs.crunchMinutes.filter {
            case (_, cm) => cm.lastUpdated.getOrElse(1L) > millis && start <= cm.minute && cm.minute < end
          }.values.toSet
          val updatedStaff = cs.staffMinutes.filter {
            case (_, sm) => sm.lastUpdated.getOrElse(1L) > millis && start <= sm.minute && sm.minute < end
          }.values.toSet
          if (updatedFlights.nonEmpty || updatedCrunch.nonEmpty) {
            val flightsLatest = if (updatedFlights.nonEmpty) updatedFlights.map(_.lastUpdated.getOrElse(1L)).max else 0L
            val crunchLatest = if (updatedCrunch.nonEmpty) updatedCrunch.map(_.lastUpdated.getOrElse(1L)).max else 0L
            val staffLatest = if (updatedStaff.nonEmpty) updatedStaff.map(_.lastUpdated.getOrElse(1L)).max else 0L
            val latestUpdate = List(flightsLatest, crunchLatest, staffLatest).max
            logInfo(s"latestUpdate: ${SDate(latestUpdate).toLocalDateTimeString()}")
            Option(CrunchUpdates(latestUpdate, updatedFlights, updatedCrunch, updatedStaff))
          } else None
        case None => None
      }
      sender() ! updates

    case SaveSnapshotSuccess(md) =>
      logInfo(s"Snapshot success $md")

    case SaveSnapshotFailure(md, cause) =>
      logInfo(s"Snapshot failed $md\n$cause")

    case DeleteSnapshotsSuccess(_) =>
      logInfo(s"Purged snapshots")

    case u =>
      log.warn(s"Received unexpected message $u")
  }

  def newPortState(flightUpdates: FlightsWithSplits): PortState = PortState(flightUpdates.flights.map(f => (f.apiFlight.uniqueId, f)).toMap, Map(), Map())

  def newPortState(sms: StaffMinutes): PortState = PortState(Map(), Map(), sms.minutes.map(cm => (cm.key, cm)).toMap)

  def newPortState(drms: DeskRecMinutes): PortState = PortState(Map(), newCrunchMinutes(drms), Map())

  def newPortState(sims: SimulationMinutes): PortState = PortState(Map(), newCrunchMinutes(sims), Map())

  def updatePortState(flightRemovals: FlightRemovals, portState: PortState): PortState = {
    val updatedFlights = portState.flights.filterKeys(id => !flightRemovals.idsToRemove.contains(id))
    portState.copy(flights = updatedFlights)
  }

  def updatePortState(flightUpdates: FlightsWithSplits, portState: PortState): PortState = {
    val updatedFlights = flightUpdates.flights.foldLeft(portState.flights) {
      case (soFar, updatedFlight) => soFar.updated(updatedFlight.apiFlight.uniqueId, updatedFlight)
    }
    portState.copy(flights = updatedFlights)
  }

  def updatePortState(sms: StaffMinutes, portState: PortState): PortState = {
    val updatedSms = sms.minutes.foldLeft(portState.staffMinutes) {
      case (soFar, updatedSm) => soFar.updated(updatedSm.key, updatedSm)
    }
    val updatedPortState = portState.copy(staffMinutes = updatedSms)
    updatedPortState
  }

  def updatePortState(drms: DeskRecMinutes, portState: PortState): PortState = {
    val updatedCms = updateCrunchMinutes(drms, portState.crunchMinutes)
    portState.copy(crunchMinutes = updatedCms)
  }

  def updatePortState(sims: SimulationMinutes, portState: PortState): PortState = {
    val updatedCms = updateCrunchMinutes(sims, portState.crunchMinutes)
    portState.copy(crunchMinutes = updatedCms)
  }

  def updatePortState(actDesks: ActualDeskStats, portState: PortState): PortState = {
    val updatedCms = updateCrunchMinutes(actDesks, portState.crunchMinutes)
    portState.copy(crunchMinutes = updatedCms)
  }

  def newCrunchMinutes(drms: DeskRecMinutes): Map[Int, CrunchMinute] = drms
    .minutes
    .map(CrunchMinute(_))
    .map(cm => (cm.key, cm))
    .toMap

  def newCrunchMinutes(sims: SimulationMinutes): Map[Int, CrunchMinute] = sims
    .minutes
    .map(CrunchMinute(_))
    .map(cm => (cm.key, cm))
    .toMap

  def updateCrunchMinutes(drms: DeskRecMinutes, crunchMinutes: Map[Int, CrunchMinute]): Map[Int, CrunchMinute] = drms
    .minutes
    .foldLeft(crunchMinutes) {
      case (soFar, updatedDrm) =>
        val maybeMinute: Option[CrunchMinute] = soFar.get(updatedDrm.key)
        val mergedCm: CrunchMinute = mergeMinute(maybeMinute, updatedDrm)
        soFar.updated(updatedDrm.key, mergedCm)
    }

  def updateCrunchMinutes(drms: SimulationMinutes, crunchMinutes: Map[Int, CrunchMinute]): Map[Int, CrunchMinute] = drms
    .minutes
    .foldLeft(crunchMinutes) {
      case (soFar, updatedDrm) =>
        val maybeMinute: Option[CrunchMinute] = soFar.get(updatedDrm.key)
        val mergedCm: CrunchMinute = mergeMinute(maybeMinute, updatedDrm)
        soFar.updated(updatedDrm.key, mergedCm)
    }

  def updateCrunchMinutes(actDesks: ActualDeskStats, crunchMinutes: Map[Int, CrunchMinute]): Map[Int, CrunchMinute] = {
    val updatedCrunchMinutes = actDesks
      .desks
      .flatMap {
        case (tn, terminalActDesks) => terminalActDesks.flatMap {
          case (qn, queueActDesks) => crunchMinutesWithActDesks(queueActDesks, crunchMinutes, qn, tn)
        }
      }

    updatedCrunchMinutes.foldLeft(crunchMinutes) {
      case (soFar, updatedCm) => soFar.updated(updatedCm.key, updatedCm)
    }
  }

  def crunchMinutesWithActDesks(queueActDesks: Map[MillisSinceEpoch, DeskStat], crunchMinutes: Map[Int, CrunchMinute], terminalName: TerminalName, queueName: QueueName): immutable.Iterable[CrunchMinute] = {
    queueActDesks
      .map {
        case (millis, deskStat) =>
          crunchMinutes.values.find(cm => cm.minute == millis && cm.queueName == queueName && cm.terminalName == terminalName).map(cm =>
            cm.copy(actDesks = deskStat.desks, actWait = deskStat.waitTime))
      }
      .collect {
        case Some(cm) => cm
      }
  }

  def mergeMinute(maybeMinute: Option[CrunchMinute], updatedDrm: DeskRecMinute): CrunchMinute = maybeMinute
    .map(existingCm => existingCm.copy(paxLoad = updatedDrm.paxLoad, workLoad = updatedDrm.workLoad, deskRec = updatedDrm.deskRec, waitTime = updatedDrm.waitTime))
    .getOrElse(CrunchMinute(updatedDrm))

  def mergeMinute(maybeMinute: Option[CrunchMinute], updatedDrm: SimulationMinute): CrunchMinute = maybeMinute
    .map(existingCm => existingCm.copy(deployedDesks = Option(updatedDrm.desks), deployedWait = Option(updatedDrm.waitTime)))
    .getOrElse(CrunchMinute(updatedDrm))

  def stateForPeriod(start: MillisSinceEpoch, end: MillisSinceEpoch): Option[PortState] = state.map {
    case PortState(fs, ms, ss) => PortState(
      flights = fs.filter {
        case (_, f) => start <= f.apiFlight.PcpTime && f.apiFlight.PcpTime < end
      },
      crunchMinutes = ms.filter {
        case (_, m) =>
          start <= m.minute && m.minute < end
      },
      staffMinutes = ss.filter {
        case (_, m) =>
          start <= m.minute && m.minute < end
      }
    )
  }

  def setStateFromSnapshot(snapshot: Any, timeWindowEnd: Option[SDateLike] = None): Unit = {
    snapshot match {
      case sm: CrunchStateSnapshotMessage =>
        logInfo(s"Using snapshot to restore")
        state = Option(snapshotMessageToState(sm, timeWindowEnd))
      case somethingElse =>
        logInfo(s"Ignoring unexpected snapshot ${somethingElse.getClass}")
    }
  }

  def stateFromDiff(cdm: CrunchDiffMessage, existingState: Option[PortState]): Option[PortState] = {
    logInfo(s"Unpacking CrunchDiffMessage")
    val diff = crunchDiffFromMessage(cdm)
    logInfo(s"Unpacked CrunchDiffMessage - " +
      s"${diff.crunchMinuteUpdates.size} crunch minute updates, " +
      s"${diff.staffMinuteUpdates.size} staff minute updates, " +
      s"${diff.flightRemovals.size} flight removals, " +
      s"${diff.flightUpdates.size} flight updates")
    val newState = existingState match {
      case None =>
        logInfo(s"Creating an empty PortState to apply CrunchDiff")
        Option(PortState(
          flights = applyFlightsWithSplitsDiff(diff, Map()),
          crunchMinutes = applyCrunchDiff(diff, Map()),
          staffMinutes = applyStaffDiff(diff, Map())
        ))
      case Some(ps) =>
        logInfo(s"Applying CrunchDiff to PortState")
        val newPortState = PortState(
          flights = applyFlightsWithSplitsDiff(diff, ps.flights),
          crunchMinutes = applyCrunchDiff(diff, ps.crunchMinutes),
          staffMinutes = applyStaffDiff(diff, ps.staffMinutes))
        logInfo(s"Finished applying CrunchDiff to PortState: ${newPortState.flights.size} flights, ${newPortState.staffMinutes.size} staff minutes, ${newPortState.crunchMinutes.size} crunch minutes")
        Option(newPortState)
    }
    newState
  }

  def crunchDiffFromMessage(diffMessage: CrunchDiffMessage): CrunchDiff = CrunchDiff(
    flightRemovals = diffMessage.flightIdsToRemove.map(RemoveFlight).toSet,
    flightUpdates = diffMessage.flightsToUpdate.map(flightWithSplitsFromMessage).toSet,
    crunchMinuteUpdates = diffMessage.crunchMinutesToUpdate.map(crunchMinuteFromMessage).toSet,
    staffMinuteUpdates = diffMessage.staffMinutesToUpdate.map(staffMinuteFromMessage).toSet
  )

  def updateStateFromPortState(newState: PortState): Unit = {
    val existingState = state match {
      case None =>
        logInfo(s"updating from no existing state")
        PortState(Map(), Map(), Map())
      case Some(s) => s
    }
    val crunchesToUpdate = crunchMinutesDiff(existingState.crunchMinutes, newState.crunchMinutes)
    val staffToUpdate = staffMinutesDiff(existingState.staffMinutes, newState.staffMinutes)
    val (flightsToRemove, flightsToUpdate) = flightsDiff(existingState.flights, newState.flights)
    val diff = CrunchDiff(flightsToRemove, flightsToUpdate, crunchesToUpdate, staffToUpdate)

    val cmsFromDiff = applyCrunchDiff(diff, existingState.crunchMinutes)
    val smsFromDiff = applyStaffDiff(diff, existingState.staffMinutes)
    val flightsFromDiff = applyFlightsWithSplitsDiff(diff, existingState.flights)

    val diffToPersist = CrunchDiffMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      crunchStart = Option(0),
      flightIdsToRemove = diff.flightRemovals.map(rf => rf.flightId).toList,
      flightsToUpdate = diff.flightUpdates.map(FlightMessageConversion.flightWithSplitsToMessage).toList,
      crunchMinutesToUpdate = diff.crunchMinuteUpdates.map(crunchMinuteToMessage).toList,
      staffMinutesToUpdate = diff.staffMinuteUpdates.map(staffMinuteToMessage).toList
    )

    val filteredStaffMinutes = Crunch.purgeExpiredMinutes(smsFromDiff, now, expireAfterMillis)
    val filteredCrunchMinutes = Crunch.purgeExpiredMinutes(cmsFromDiff, now, expireAfterMillis)

    //    println(s"existing staffminutes: ${existingState.staffMinutes}")
    val updatedState = existingState.copy(
      flights = flightsFromDiff,
      crunchMinutes = filteredCrunchMinutes,
      staffMinutes = filteredStaffMinutes
    )
    //    println(s"updated staffminutes: ${updatedState.staffMinutes}")

    persist(diffToPersist) { (diff: CrunchDiffMessage) =>
      logInfo(s"Persisting ${diff.getClass}: ${diff.crunchMinutesToUpdate.length} cms, ${diff.flightsToUpdate.length} fs, ${diff.staffMinutesToUpdate.length} sms, ${diff.flightIdsToRemove.length} removed fms")

      context.system.eventStream.publish(diff)
      if (diff.crunchMinutesToUpdate.length > 20000 || (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0)) {
        val snapshotMessage: CrunchStateSnapshotMessage = portStateToSnapshotMessage(updatedState)
        logInfo(s"Saving PortState snapshot: ${snapshotMessage.crunchMinutes.length} cms, ${snapshotMessage.flightWithSplits.length} fs, ${snapshotMessage.staffMinutes.length} sms")
        saveSnapshot(snapshotMessage)
        if (purgePreviousSnapshots) {
          val maxSequenceNr = lastSequenceNr
          logInfo(s"Purging snapshots with sequence number < $maxSequenceNr")
          deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = maxSequenceNr))
        }
      }
    }

    state = Option(updatedState)
  }

  def removeCrunchMinuteToMessage(rc: RemoveCrunchMinute): RemoveCrunchMinuteMessage = RemoveCrunchMinuteMessage(Option(rc.terminalName), Option(rc.queueName), Option(rc.minute))

  def crunchMinuteToMessage(cm: CrunchMinute): CrunchMinuteMessage = CrunchMinuteMessage(
    terminalName = Option(cm.terminalName),
    queueName = Option(cm.queueName),
    minute = Option(cm.minute),
    paxLoad = Option(cm.paxLoad),
    workLoad = Option(cm.workLoad),
    deskRec = Option(cm.deskRec),
    waitTime = Option(cm.waitTime),
    simDesks = cm.deployedDesks,
    simWait = cm.deployedWait,
    actDesks = cm.actDesks,
    actWait = cm.actWait
  )

  def staffMinuteToMessage(sm: StaffMinute): StaffMinuteMessage = StaffMinuteMessage(
    terminalName = Option(sm.terminalName),
    minute = Option(sm.minute),
    shifts = Option(sm.shifts),
    fixedPoints = Option(sm.fixedPoints),
    movements = Option(sm.movements))

  def portStateToSnapshotMessage(portState: PortState) = CrunchStateSnapshotMessage(
    Option(0L),
    Option(0),
    portState.flights.values.toList.map(flight => FlightMessageConversion.flightWithSplitsToMessage(flight)),
    portState.crunchMinutes.values.toList.map(crunchMinuteToMessage),
    portState.staffMinutes.values.toList.map(staffMinuteToMessage)
  )

  def snapshotMessageToState(sm: CrunchStateSnapshotMessage, optionalTimeWindowEnd: Option[SDateLike]): PortState = {
    logInfo(s"Unwrapping flights messages")
    val flights = optionalTimeWindowEnd match {
      case None =>
        sm.flightWithSplits.map(fwsm => {
          val fws = flightWithSplitsFromMessage(fwsm)
          (fws.apiFlight.uniqueId, fws)
        }).toMap
      case Some(timeWindowEnd) =>
        val windowEndMillis = timeWindowEnd.millisSinceEpoch
        sm.flightWithSplits.collect {
          case fwsm if fwsm.flight.map(fm => fm.pcpTime.getOrElse(0L)).getOrElse(0L) <= windowEndMillis =>
            val fws = flightWithSplitsFromMessage(fwsm)
            (fws.apiFlight.uniqueId, fws)
        }.toMap
    }

    logInfo(s"Unwrapping minutes messages")
    val crunchMinutes = sm.crunchMinutes.map(cmm => {
      val cm = crunchMinuteFromMessage(cmm)
      (cm.key, cm)
    }).toMap
    val staffMinutes = sm.staffMinutes.map(cmm => {
      val sm = staffMinuteFromMessage(cmm)
      (sm.key, sm)
    }).toMap
    logInfo(s"Finished unwrapping messages")
    PortState(flights, crunchMinutes, staffMinutes)
  }

  def crunchMinuteFromMessage(cmm: CrunchMinuteMessage): CrunchMinute = CrunchMinute(
    terminalName = cmm.terminalName.getOrElse(""),
    queueName = cmm.queueName.getOrElse(""),
    minute = cmm.minute.getOrElse(0L),
    paxLoad = cmm.paxLoad.getOrElse(0d),
    workLoad = cmm.workLoad.getOrElse(0d),
    deskRec = cmm.deskRec.getOrElse(0),
    waitTime = cmm.waitTime.getOrElse(0),
    deployedDesks = cmm.simDesks,
    deployedWait = cmm.simWait,
    actDesks = cmm.actDesks,
    actWait = cmm.actWait
  )

  def staffMinuteFromMessage(smm: StaffMinuteMessage): StaffMinute = StaffMinute(
    terminalName = smm.terminalName.getOrElse(""),
    minute = smm.minute.getOrElse(0L),
    shifts = smm.shifts.getOrElse(0),
    fixedPoints = smm.fixedPoints.getOrElse(0),
    movements = smm.movements.getOrElse(0)
  )

  def flightWithSplitsFromMessage(fm: FlightWithSplitsMessage): ApiFlightWithSplits = {
    ApiFlightWithSplits(
      FlightMessageConversion.flightMessageToApiFlight(fm.flight.get),
      fm.splits.map(sm => splitMessageToApiSplits(sm)).toSet,
      None
    )
  }
}

object SplitsConversion {
  def splitMessageToApiSplits(sm: SplitMessage): ApiSplits = {
    val splitSource = sm.source.getOrElse("") match {
      case SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages_Old => SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
      case s => s
    }

    ApiSplits(
      sm.paxTypeAndQueueCount.map(ptqcm => ApiPaxTypeAndQueueCount(
        PaxType(ptqcm.paxType.getOrElse("")),
        ptqcm.queueType.getOrElse(""),
        ptqcm.paxValue.getOrElse(0d),
        None
      )).toSet,
      splitSource,
      sm.eventType,
      SplitStyle(sm.style.getOrElse(""))
    )
  }
}

case object GetPortWorkload
