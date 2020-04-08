package actors

import actors.FlightMessageConversion.flightWithSplitsFromMessage
import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.{SplitSource, SplitSources}
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState._
import services.SDate
import services.graphstages.Crunch

object PortStateMessageConversion {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def snapshotMessageToState(sm: CrunchStateSnapshotMessage,
                             optionalTimeWindowEnd: Option[SDateLike],
                             state: PortStateMutable): Unit = {
    state.clear()

    log.debug(s"Unwrapping flights messages")
    optionalTimeWindowEnd match {
      case None =>
        state.flights ++= sm.flightWithSplits.map(message => {
          val fws = flightWithSplitsFromMessage(message)
          (fws.unique, fws)
        })
        state.crunchMinutes ++= sm.crunchMinutes.collect {
          case message =>
            val cm = crunchMinuteFromMessage(message)
            (cm.key, cm)
        }
        state.staffMinutes ++= sm.staffMinutes.collect {
          case message if message.getShifts > 0 =>
            val sm = staffMinuteFromMessage(message)
            (sm.key, sm)
        }
      case Some(timeWindowEnd) =>
        val windowEndMillis = timeWindowEnd.millisSinceEpoch
        state.flights ++= sm.flightWithSplits.collect {
          case message if message.flight.map(fm => fm.pcpTime.getOrElse(0L)).getOrElse(0L) <= windowEndMillis =>
            val fws = flightWithSplitsFromMessage(message)
            (fws.unique, fws)
        }
        state.crunchMinutes ++= sm.crunchMinutes.collect {
          case message if message.getMinute <= windowEndMillis =>
            val cm = crunchMinuteFromMessage(message)
            (cm.key, cm)
        }
        state.staffMinutes ++= sm.staffMinutes.collect {
          case message if message.getMinute <= windowEndMillis =>
            val sm = staffMinuteFromMessage(message)
            (sm.key, sm)
        }
    }

    log.debug(s"Finished unwrapping messages")
  }

  def crunchMinuteFromMessage(cmm: CrunchMinuteMessage): CrunchMinute = {
    val minute = cmm.minute.getOrElse(0L)
    val roundedMinute = minute - (minute % 60000)
    CrunchMinute(
      terminal = Terminal(cmm.terminalName.getOrElse("")),
      queue = Queue(cmm.queueName.getOrElse("")),
      minute = roundedMinute,
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
    val minute = smm.minute.getOrElse(0L)
    val roundedMinute: Long = minute - (minute % 60000)
    StaffMinute(
      terminal = Terminal(smm.terminalName.getOrElse("")),
      minute = roundedMinute,
      shifts = smm.shifts.getOrElse(0),
      fixedPoints = smm.fixedPoints.getOrElse(0),
      movements = smm.movements.getOrElse(0)
      )
  }

  def staffMinuteToMessage(sm: StaffMinute): StaffMinuteMessage = StaffMinuteMessage(
    terminalName = Option(sm.terminal.toString),
    minute = Option(sm.minute),
    shifts = Option(sm.shifts),
    fixedPoints = Option(sm.fixedPoints),
    movements = Option(sm.movements))

  def snapshotMessageToFlightsState(sm: CrunchStateSnapshotMessage, state: PortStateMutable): Unit = {
    state.clear()
    state.flights ++= flightsFromMessages(sm.flightWithSplits)
  }

  def flightsFromMessages(flightMessages: Seq[FlightWithSplitsMessage]): Seq[(UniqueArrival, ApiFlightWithSplits)] = flightMessages.map(message => {
    val fws = flightWithSplitsFromMessage(message)
    (fws.unique, fws)
  })

  def portStateToSnapshotMessage(portState: PortStateMutable): CrunchStateSnapshotMessage = {
    CrunchStateSnapshotMessage(
      Option(0L),
      Option(0),
      portState.flights.all.values.toList.map(flight => FlightMessageConversion.flightWithSplitsToMessage(flight)),
      portState.crunchMinutes.all.values.toList.map(crunchMinuteToMessage),
      portState.staffMinutes.all.values.toList.map(staffMinuteToMessage)
      )
  }

  def splitMessageToApiSplits(sm: SplitMessage): Splits = {
    val splitSource = SplitSource(sm.source.getOrElse("")) match {
      case SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages_Old => SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
      case s => s
    }

    Splits(
      sm.paxTypeAndQueueCount.map(ptqcm => ApiPaxTypeAndQueueCount(
        PaxType(ptqcm.paxType.getOrElse("")),
        Queue(ptqcm.queueType.getOrElse("")),
        ptqcm.paxValue.getOrElse(0d),
        None
        )).toSet,
      splitSource,
      sm.eventType.map(EventType(_)),
      SplitStyle(sm.style.getOrElse(""))
      )
  }

  def crunchMinuteToMessage(cm: CrunchMinute): CrunchMinuteMessage = CrunchMinuteMessage(
    terminalName = Option(cm.terminal.toString),
    queueName = Option(cm.queue.toString),
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
