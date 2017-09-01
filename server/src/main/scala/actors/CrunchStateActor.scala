package actors

import akka.actor._
import akka.persistence._
import controllers.GetTerminalCrunch
import drt.shared.FlightsApi._
import drt.shared._
import server.protobuf.messages.CrunchState._
import services.Crunch._
import services.SDate
import services.workloadcalculator.PaxLoadCalculator.MillisSinceEpoch

import scala.collection.immutable._
import scala.language.postfixOps


class CrunchStateActor(portQueues: Map[TerminalName, Seq[QueueName]]) extends PersistentActor with ActorLogging {
  override def persistenceId: String = "crunch-state"

  var state: Option[CrunchState] = None

  val snapshotInterval = 25

  val oneDayMinutes = 1440

  override def receiveRecover: Receive = {
    case SnapshotOffer(metadata, snapshot) =>
      log.info(s"Received SnapshotOffer ${metadata.timestamp} with ${snapshot.getClass}")
      snapshot match {
        case sm@CrunchStateSnapshotMessage(_, _, _, _) =>
          log.info(s"Using snapshot to restore")
          state = Option(snapshotMessageToState(sm))
        case somethingElse =>
          log.info(s"Ignoring unexpected snapshot ${somethingElse.getClass}")
      }

    case cdm@ CrunchDiffMessage(_, flightIdsToRemove, flightsToUpdate, crunchMinutesToRemove, crunchMinutesToUpdate) =>
      val diff = CrunchDiff(
        flightRemovals = flightIdsToRemove.map(RemoveFlight(_)).toSet,
        flightUpdates = flightsToUpdate.map(flightWithSplitsFromMessage).toSet,
        crunchMinuteRemovals = crunchMinutesToRemove.map(m => RemoveCrunchMinute(m.terminalName.getOrElse(""), m.queueName.getOrElse(""), m.minute.getOrElse(0L))).toSet,
        crunchMinuteUpdates = crunchMinutesToUpdate.map(crunchMinuteFromMessage).toSet
      )
      val newState = state match {
        case None =>
          log.error(s"We don't have a CrunchState to apply the diff to")
          None
        case Some(cs) =>
          log.info(s"Applying CrunchDiff to CrunchState")
          Option(cs.copy(
            flights = applyFlightsDiff(diff, cs.flights),
            crunchMinutes = applyCrunchDiff(diff, cs.crunchMinutes)))
      }
      state = newState

    case RecoveryCompleted =>
      log.info("Finished restoring crunch state")

    case u =>
      log.info(s"recovery: received unexpected ${u.getClass}")
  }

  def updateStateFromCrunchState(newState: CrunchState): Unit = {
    val updatedState = state match {
      case None =>
        log.info(s"updating from no existing state")
        newState
      case Some(existingState) =>
        val cd = crunchMinutesDiff(existingState.crunchMinutes, newState.crunchMinutes)
        val fd = flightsDiff(existingState.flights, newState.flights)
        val diff = CrunchDiff(fd._1, fd._2, cd._1, cd._2)
        val cmsFromDiff = applyCrunchDiff(diff, existingState.crunchMinutes)
        if (cmsFromDiff != newState.crunchMinutes) {
          log.error(s"new CrunchMinutes do not match update from diff")
        } else {
          log.info(s"new CrunchMinutes match update from diff!")
        }
        val flightsFromDiff = applyFlightsDiff(diff, existingState.flights)
        if (flightsFromDiff != newState.flights) {
          log.error(s"new Flights do not match update from diff")
        } else {
          log.info(s"new Flights match update from diff!")
        }

        val diffToPersist = CrunchDiffMessage(
          createdAt = Option(SDate.now().millisSinceEpoch),
          fd._1.map(rf => rf.flightId).toList,
          fd._2.map(FlightMessageConversion.flightWithSplitsToMessage).toList,
          cd._1.map(rc => RemoveCrunchMinuteMessage(Option(rc.terminalName), Option(rc.queueName), Option(rc.minute))).toList,
          cd._2.map(cm => CrunchMinuteMessage(Option(cm.terminalName), Option(cm.queueName), Option(cm.minute), Option(cm.paxLoad), Option(cm.workLoad), Option(cm.deskRec), Option(cm.waitTime))).toList
        )

        persist(diffToPersist) { (diff: CrunchDiffMessage) =>
          log.info(s"persisting ${diff.getClass}")
          context.system.eventStream.publish(diff)
        }

        existingState.copy(
          crunchFirstMinuteMillis = newState.crunchFirstMinuteMillis,
          crunchMinutes = cmsFromDiff,
          flights = flightsFromDiff)
    }

    state = Option(updatedState)
  }

  override def receiveCommand: Receive = {
    case cs@CrunchState(_, _, _, _) =>
      log.info(s"received CrunchState. storing")
      updateStateFromCrunchState(cs)
      saveSnapshotAtInterval(cs)

    case GetFlights =>
      state match {
        case Some(CrunchState(_, _, flights, _)) =>
          sender() ! FlightsWithSplits(flights.toList)
        case None => FlightsNotReady
      }

    case GetPortWorkload =>
      state match {
        case Some(CrunchState(_, _, _, crunchMinutes)) =>
          sender() ! portWorkload(crunchMinutes)
        case None =>
          sender() ! WorkloadsNotReady()
      }

    case GetTerminalCrunch(terminalName) =>
      state match {
        case Some(CrunchState(startMillis, _, _, crunchMinutes)) =>
          sender() ! queueCrunchResults(terminalName, startMillis, crunchMinutes)
        case _ =>
          sender() ! List[(QueueName, Either[NoCrunchAvailable, CrunchResult])]()
      }

    case SaveSnapshotSuccess(md) =>
      log.info(s"Snapshot success $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Snapshot failed $md\n$cause")

    case u =>
      log.warning(s"unexpected message $u")
  }

  def saveSnapshotAtInterval(cs: CrunchState) = {
    //    if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
    val snapshotMessage: CrunchStateSnapshotMessage = crunchStateToSnapshotMessage(cs)
    log.info("Saving CrunchState snapshot")
    saveSnapshot(snapshotMessage)
    //    }
  }

  def portWorkload(crunchMinutes: Set[CrunchMinute]) = crunchMinutes
    .groupBy(_.terminalName)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queueName)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val paxLoad = sortedCms.map {
                case CrunchMinute(_, _, m, pl, _, _, _) => Pax(m, pl)
              }.toIndexedSeq
              val workLoad = sortedCms.map {
                case CrunchMinute(_, _, m, _, wl, _, _) => WL(m, wl)
              }
              (qn, (workLoad, paxLoad))
          }
        (tn, terminalLoads)
    }

  def queueCrunchResults(terminalName: TerminalName,
                         startMillis: MillisSinceEpoch,
                         crunchMinutes: Set[CrunchMinute]): Seq[(QueueName, Either[NoCrunchAvailable, CrunchResult])] = crunchMinutes
    .groupBy(_.terminalName).getOrElse(terminalName, Set[CrunchMinute]())
    .groupBy(_.queueName)
    .map {
      case (qn, qms) =>
        if (qms.size == oneDayMinutes) {
          val sortedCms = qms.toList.sortBy(_.minute)
          val desks = sortedCms.map {
            case CrunchMinute(_, _, _, _, _, dr, _) => dr
          }.toIndexedSeq
          val waits = sortedCms.map {
            case CrunchMinute(_, _, _, _, _, _, wt) => wt
          }
          (qn, Right(CrunchResult(startMillis, oneMinute, desks, waits)))
        } else {
          (qn, Left(NoCrunchAvailable()))
        }
    }.toList

  def oneDayOfMinutes = {
    0 until 1440
  }

  def crunchStateToSnapshotMessage(crunchState: CrunchState) = CrunchStateSnapshotMessage(
    Option(crunchState.crunchFirstMinuteMillis),
    Option(crunchState.numberOfMinutes),
    crunchState.flights.toList.map(flight => FlightMessageConversion.flightWithSplitsToMessage(flight)),
    crunchState.crunchMinutes.toList.map(cm => CrunchMinuteMessage(
      Option(cm.terminalName),
      Option(cm.queueName),
      Option(cm.minute),
      Option(cm.paxLoad),
      Option(cm.workLoad),
      Option(cm.deskRec),
      Option(cm.waitTime)
    ))
  )

  def snapshotMessageToState(sm: CrunchStateSnapshotMessage) = CrunchState(
    sm.crunchStart.getOrElse(0L),
    sm.numberOfMinutes.getOrElse(0),
    sm.flightWithSplits.map(flightWithSplitsFromMessage).toSet,
    sm.crunchMinutes.map(crunchMinuteFromMessage).toSet
  )

  def crunchMinuteFromMessage(cmm: CrunchMinuteMessage) = {
    CrunchMinute(
      cmm.terminalName.getOrElse(""),
      cmm.queueName.getOrElse(""),
      cmm.minute.getOrElse(0L),
      cmm.paxLoad.getOrElse(0d),
      cmm.workLoad.getOrElse(0d),
      cmm.deskRec.getOrElse(0),
      cmm.waitTime.getOrElse(0)
    )
  }

  def flightWithSplitsFromMessage(fm: FlightWithSplitsMessage) = {
    ApiFlightWithSplits(
      FlightMessageConversion.flightMessageToApiFlight(fm.flight.get),
      fm.splits.map(sm => splitMessageToApiSplits(sm)).toList
    )
  }

  def splitMessageToApiSplits(sm: SplitMessage) = {
    ApiSplits(
      sm.paxTypeAndQueueCount.map(ptqcm => ApiPaxTypeAndQueueCount(
        PaxType(ptqcm.paxType.getOrElse("")),
        ptqcm.queueType.getOrElse(""),
        ptqcm.paxValue.getOrElse(0d)
      )).toList,
      sm.source.getOrElse(""),
      SplitStyle(sm.style.getOrElse(""))
    )
  }

  //  def persistState(cs: CrunchState) = {
  //    persist(cs) { (crunchState: CrunchState) =>
  //      log.info(s"persisting ${snapshotMessage.getClass}")
  //      context.system.eventStream.publish(snapshotMessage)

  //    }
  //  }
}

case object GetPortWorkload
