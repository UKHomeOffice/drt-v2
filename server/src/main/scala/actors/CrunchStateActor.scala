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

import scala.collection.immutable._
import scala.language.postfixOps

class CrunchStateActor(val snapshotInterval: Int,
                       name: String,
                       portQueues: Map[TerminalName, Seq[QueueName]],
                       now: () => SDateLike,
                       expireAfterMillis: Long,
                       purgePreviousSnapshots: Boolean) extends PersistentActor {
  override def persistenceId: String = name

  val log: Logger = LoggerFactory.getLogger(s"$name-$getClass")

  var state: Option[PortState] = None

  override def receiveRecover: Receive = {
    case SnapshotOffer(metadata, snapshot) =>
      log.info(s"Recovery: received SnapshotOffer ${metadata.timestamp} with ${snapshot.getClass}")
      setStateFromSnapshot(snapshot)

    case cdm: CrunchDiffMessage =>
      log.info(s"Recovery: received CrunchDiffMessage")
      val newState = stateFromDiff(cdm, state)
      logRecoveryState(newState)
      state = newState

    case RecoveryCompleted =>
      log.info("Recovery: Finished restoring crunch state")

    case u =>
      log.info(s"Recovery: received unexpected ${u.getClass}")
  }

  def logRecoveryState(optionalState: Option[PortState]): Unit = optionalState match {
    case None => log.info(s"Recovery: state is None")
    case Some(s) =>
      val apiCount = s.flights.count {
        case (_, f) => f.splits.exists {
          case ApiSplits(_, SplitSources.ApiSplitsWithCsvPercentage, _, _) => true
          case _ => false
        }
      }
      log.info(s"Recovery: state contains ${s.flights.size} flights " +
        s"with $apiCount Api splits " +
        s", ${s.crunchMinutes.size} crunch minutes " +
        s", ${s.staffMinutes.size} staff minutes ")
  }


  override def receiveCommand: Receive = {
    case cs: PortState =>
      log.info(s"Received PortState. storing")
      updateStateFromPortState(cs)

    case GetState =>
      log.info(s"Received GetState request. Replying with ${state.map(s => s"PortState containing ${s.crunchMinutes.size} crunch minutes")}")
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
            log.info(s"latestUpdate: ${SDate(latestUpdate).toLocalDateTimeString()}")
            Option(CrunchUpdates(latestUpdate, updatedFlights, updatedCrunch, updatedStaff))
          } else None
        case None => None
      }
      sender() ! updates

    case SaveSnapshotSuccess(md) =>
      log.info(s"Snapshot success $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Snapshot failed $md\n$cause")

    case DeleteSnapshotsSuccess(_) =>
      log.info(s"Purged snapshots")

    case u =>
      log.warn(s"Received unexpected message $u")
  }

  def stateForPeriod(start: MillisSinceEpoch, end: MillisSinceEpoch): Option[PortState] = {
    state.map {
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
  }

  def setStateFromSnapshot(snapshot: Any, timeWindowEnd: Option[SDateLike] = None): Unit = {
    snapshot match {
      case sm: CrunchStateSnapshotMessage =>
        log.info(s"Using snapshot to restore")
        state = Option(snapshotMessageToState(sm, timeWindowEnd))
      case somethingElse =>
        log.info(s"Ignoring unexpected snapshot ${somethingElse.getClass}")
    }
  }

  def stateFromDiff(cdm: CrunchDiffMessage, existingState: Option[PortState]): Option[PortState] = {
    log.info(s"Unpacking CrunchDiffMessage")
    val diff = crunchDiffFromMessage(cdm)
    log.info(s"Unpacked CrunchDiffMessage - " +
      s"${diff.crunchMinuteUpdates.size} crunch minute updates, " +
      s"${diff.staffMinuteUpdates.size} staff minute updates, " +
      s"${diff.flightRemovals.size} flight removals, " +
      s"${diff.flightUpdates.size} flight updates")
    val newState = existingState match {
      case None =>
        log.info(s"Creating an empty PortState to apply CrunchDiff")
        Option(PortState(
          flights = applyFlightsWithSplitsDiff(diff, Map()),
          crunchMinutes = applyCrunchDiff(diff, Map()),
          staffMinutes = applyStaffDiff(diff, Map())
        ))
      case Some(ps) =>
        log.info(s"Applying CrunchDiff to PortState")
        val newPortState = PortState(
          flights = applyFlightsWithSplitsDiff(diff, ps.flights),
          crunchMinutes = applyCrunchDiff(diff, ps.crunchMinutes),
          staffMinutes = applyStaffDiff(diff, ps.staffMinutes))
        log.info(s"Finished applying CrunchDiff to PortState: ${newPortState.flights.size} flights, ${newPortState.staffMinutes.size} staff minutes, ${newPortState.crunchMinutes.size} crunch minutes")
        Option(newPortState)
    }
    newState
  }

  def crunchDiffFromMessage(diffMessage: CrunchDiffMessage): CrunchDiff = {
    CrunchDiff(
      flightRemovals = diffMessage.flightIdsToRemove.map(RemoveFlight).toSet,
      flightUpdates = diffMessage.flightsToUpdate.map(flightWithSplitsFromMessage).toSet,
      crunchMinuteUpdates = diffMessage.crunchMinutesToUpdate.map(crunchMinuteFromMessage).toSet,
      staffMinuteUpdates = diffMessage.staffMinutesToUpdate.map(staffMinuteFromMessage).toSet
    )
  }

  def updateStateFromPortState(newState: PortState): Unit = {
    val existingState = state match {
      case None =>
        log.info(s"updating from no existing state")
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

    val updatedState = existingState.copy(
      flights = flightsFromDiff,
      crunchMinutes = filteredCrunchMinutes,
      staffMinutes = filteredStaffMinutes
    )

    persist(diffToPersist) { (diff: CrunchDiffMessage) =>
      log.info(s"Persisting ${diff.getClass}: ${diff.crunchMinutesToUpdate.length} cms, ${diff.flightsToUpdate.length} fs, ${diff.staffMinutesToUpdate.length} sms, ${diff.flightIdsToRemove.length} removed fms")
      context.system.eventStream.publish(diff)
      if (diff.crunchMinutesToUpdate.length > 20000 || (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0)) {
        val snapshotMessage: CrunchStateSnapshotMessage = portStateToSnapshotMessage(updatedState)
        log.info(s"Saving PortState snapshot: ${snapshotMessage.crunchMinutes.length} cms, ${snapshotMessage.flightWithSplits.length} fs, ${snapshotMessage.staffMinutes.length} sms")
        saveSnapshot(snapshotMessage)
        if (purgePreviousSnapshots) {
          val maxSequenceNr = lastSequenceNr
          log.info(s"Purging snapshots with sequence number < $maxSequenceNr")
          deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = maxSequenceNr))
        }
      }
    }

    state = Option(updatedState)
  }

  def removeCrunchMinuteToMessage(rc: RemoveCrunchMinute): RemoveCrunchMinuteMessage = {
    RemoveCrunchMinuteMessage(Option(rc.terminalName), Option(rc.queueName), Option(rc.minute))
  }

  def crunchMinuteToMessage(cm: CrunchMinute): CrunchMinuteMessage = {
    CrunchMinuteMessage(
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
  }

  def staffMinuteToMessage(sm: StaffMinute): StaffMinuteMessage = {
    StaffMinuteMessage(
      terminalName = Option(sm.terminalName),
      minute = Option(sm.minute),
      shifts = Option(sm.shifts),
      fixedPoints = Option(sm.fixedPoints),
      movements = Option(sm.movements))
  }

  def portStateToSnapshotMessage(portState: PortState) = CrunchStateSnapshotMessage(
    Option(0L),
    Option(0),
    portState.flights.values.toList.map(flight => FlightMessageConversion.flightWithSplitsToMessage(flight)),
    portState.crunchMinutes.values.toList.map(crunchMinuteToMessage),
    portState.staffMinutes.values.toList.map(staffMinuteToMessage)
  )

  def snapshotMessageToState(sm: CrunchStateSnapshotMessage, optionalTimeWindowEnd: Option[SDateLike]): PortState = {
    log.info(s"Unwrapping flights messages")
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

    log.info(s"Unwrapping minutes messages")
    val crunchMinutes = sm.crunchMinutes.map(cmm => {
      val cm = crunchMinuteFromMessage(cmm)
      (cm.key, cm)
    }).toMap
    val staffMinutes = sm.staffMinutes.map(cmm => {
      val sm = staffMinuteFromMessage(cmm)
      (sm.key, sm)
    }).toMap
    log.info(s"Finished unwrapping messages")
    PortState(flights, crunchMinutes, staffMinutes)
  }

  def crunchMinuteFromMessage(cmm: CrunchMinuteMessage): CrunchMinute = {
    CrunchMinute(
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
  }

  def staffMinuteFromMessage(smm: StaffMinuteMessage): StaffMinute = {
    StaffMinute(
      terminalName = smm.terminalName.getOrElse(""),
      minute = smm.minute.getOrElse(0L),
      shifts = smm.shifts.getOrElse(0),
      fixedPoints = smm.fixedPoints.getOrElse(0),
      movements = smm.movements.getOrElse(0)
    )
  }

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
    ApiSplits(
      sm.paxTypeAndQueueCount.map(ptqcm => ApiPaxTypeAndQueueCount(
        PaxType(ptqcm.paxType.getOrElse("")),
        ptqcm.queueType.getOrElse(""),
        ptqcm.paxValue.getOrElse(0d)
      )).toSet,
      sm.source.getOrElse(""),
      sm.eventType,
      SplitStyle(sm.style.getOrElse(""))
    )
  }
}

case object GetPortWorkload
