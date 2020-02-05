package actors.pointInTime

import actors.PortStateMessageConversion.{crunchMinuteFromMessage, flightWithSplitsFromMessage, staffMinuteFromMessage}
import actors.Sizes.oneMegaByte
import actors._
import akka.actor.Props
import akka.persistence._
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import server.protobuf.messages.CrunchState._
import services.SDate

case object GetCrunchMinutes

object CrunchStateReadActor {
  def props(snapshotInterval: Int,
            pointInTime: SDateLike,
            expireAfterMillis: Int,
            queues: Map[Terminal, Seq[Queue]],
            startMillis: MillisSinceEpoch,
            endMillis: MillisSinceEpoch): Props = Props(
    new CrunchStateReadActor(
      snapshotInterval,
      pointInTime,
      expireAfterMillis,
      queues,
      startMillis,
      endMillis
    )
  )
}

class CrunchStateReadActor(snapshotInterval: Int,
                           pointInTime: SDateLike,
                           expireAfterMillis: Int,
                           queues: Map[Terminal, Seq[Queue]],
                           startMillis: MillisSinceEpoch,
                           endMillis: MillisSinceEpoch)
  extends CrunchStateActor(
    initialMaybeSnapshotInterval = Option(snapshotInterval),
    initialSnapshotBytesThreshold = oneMegaByte,
    name = "crunch-state",
    portQueues = queues,
    now = () => pointInTime,
    expireAfterMillis = expireAfterMillis,
    purgePreviousSnapshots = false,
    forecastMaxMillis = () => endMillis) {

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: CrunchStateSnapshotMessage =>
      println(s"processSnapshotMessage total staff messages: ${snapshot.staffMinutes.length}")
      val dates = snapshot.staffMinutes.groupBy(m => SDate(m.minute.get).toISODateOnly)
      snapshot.staffMinutes.foreach(m => println(s"snapshot msg: ${SDate(m.minute.get).toISOString()} ${m.shifts.get}"))
      dates.get("2020-02-03").map { msgs =>
        msgs.sortBy(_.minute).foreach(m => println(s"snapshot msg: ${SDate(m.minute.get).toISOString()} ${m.shifts.get}"))
      }
      println(s"processSnapshotMessage got ${snapshot.staffMinutes.filter(sm => sm.minute.isDefined && sm.minute.get > 1580688000000L && sm.minute.get < 1580774400000L).size} staff minutes")

      println(s"state before: ${state.staffMinutes.all.size}")

      setStateFromSnapshot(snapshot, Option(pointInTime.addDays(2)))
      println(s"state after: ${state.staffMinutes.all.size}")

      val dates2 = state.staffMinutes.all.values.groupBy(m => SDate(m.minute).toISODateOnly)
      dates2.get("2020-02-03").map { msgs =>
        msgs.toSeq.sortBy(_.minute).foreach(m => println(s"state: ${SDate(m.minute).toISOString()} ${m.shifts}"))
      }
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case cdm@CrunchDiffMessage(createdAtOption, _, _, _, _, _, _, _) =>
      createdAtOption match {
        case Some(createdAt) if createdAt <= pointInTime.millisSinceEpoch =>
          log.debug(s"Applying crunch diff with createdAt (${SDate(createdAt).toISOString()}) <= point in time requested: ${pointInTime.toISOString()}")
          applyRecoveryDiff(cdm, endMillis)
        case Some(createdAt) =>
          log.debug(s"Ignoring crunch diff with createdAt (${SDate(createdAt).toISOString()}) > point in time requested: ${pointInTime.toISOString()}")
      }
  }

  override def postRecoveryComplete(): Unit = {
    super.postRecoveryComplete()
    logPointInTimeCompleted(pointInTime)
  }

  override def receiveCommand: Receive = {
    case SaveSnapshotSuccess =>
      log.info("Saved PortState Snapshot")

    case GetState =>
      logInfo(s"Received GetState Request (pit: ${pointInTime.toISOString()}")
      sender() ! Option(state)

    case GetPortState(start, end) =>
      logInfo(s"Received GetPortState Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriod(start, end)

    case GetPortStateForTerminal(start, end, terminalName) =>
      logInfo(s"Received GetPortState Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriodForTerminal(start, end, terminalName)

    case u =>
      log.error(s"Received unexpected message $u")
  }

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    val recovery = Recovery(
      fromSnapshot = criteria,
      replayMax = snapshotInterval)
    log.info(s"recovery: $recovery")
    recovery
  }

  override def crunchDiffFromMessage(diffMessage: CrunchDiffMessage, x: MillisSinceEpoch): (Seq[UniqueArrival], Seq[ApiFlightWithSplits], Seq[CrunchMinute], Seq[StaffMinute]) = (

    diffMessage.flightsToRemove.collect {
      case m if queues.contains(Terminal(m.getTerminalName)) => uniqueArrivalFromMessage(m)
    },
    diffMessage.flightsToUpdate.collect {
      case m if isInterestingFlightMessage(m) => flightWithSplitsFromMessage(m)
    },
    diffMessage.crunchMinutesToUpdate.collect {
      case m if isInterestingCrunchMinuteMessage(m) => crunchMinuteFromMessage(m)
    },
    diffMessage.staffMinutesToUpdate.collect {
      case m if isInterestingStaffMinuteMessage(m) => staffMinuteFromMessage(m)
    }
  )

  val isInterestingFlightMessage: FlightWithSplitsMessage => Boolean = (fm: FlightWithSplitsMessage) => {
    val flight = fm.getFlight
    queues.contains(Terminal(flight.getTerminal)) && startMillis <= flight.getPcpTime && flight.getPcpTime <= endMillis
  }

  val isInterestingCrunchMinuteMessage: CrunchMinuteMessage => Boolean = (cm: CrunchMinuteMessage) => {
    queues.contains(Terminal(cm.getTerminalName)) && startMillis <= cm.getMinute && cm.getMinute <= endMillis
  }

  val isInterestingStaffMinuteMessage: StaffMinuteMessage => Boolean = (sm: StaffMinuteMessage) => {
    queues.contains(Terminal(sm.getTerminalName)) && startMillis <= sm.getMinute && sm.getMinute <= endMillis
  }
}
