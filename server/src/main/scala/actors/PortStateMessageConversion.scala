package actors

import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.{SplitSource, SplitSources}
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState._
import services.graphstages.Crunch

object PortStateMessageConversion {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def snapshotMessageToState(sm: CrunchStateSnapshotMessage, optionalTimeWindowEnd: Option[SDateLike], state: PortStateMutable): Unit = {
    state.clear()

    log.debug(s"Unwrapping flights messages")
    optionalTimeWindowEnd match {
      case None =>
        state.flights ++= sm.flightWithSplits.map(message => {
          val fws = flightWithSplitsFromMessage(message)
          (fws.unique, fws)
        })
        state.crunchMinutes ++= sm.crunchMinutes.collect {
          case message if message.minute.getOrElse(0L) % Crunch.oneMinuteMillis == 0 =>
            val cm = crunchMinuteFromMessage(message)
            (cm.key, cm)
        }
        state.staffMinutes ++= sm.staffMinutes.collect {
          case message if message.minute.getOrElse(0L) % Crunch.oneMinuteMillis == 0 =>
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
          case message if message.minute.getOrElse(0L) % Crunch.oneMinuteMillis == 0 && message.getMinute <= windowEndMillis =>
            val cm = crunchMinuteFromMessage(message)
            (cm.key, cm)
        }
        state.staffMinutes ++= sm.staffMinutes.collect {
          case message if message.minute.getOrElse(0L) % Crunch.oneMinuteMillis == 0 && message.getMinute <= windowEndMillis =>
            val sm = staffMinuteFromMessage(message)
            (sm.key, sm)
        }
    }

    log.debug(s"Finished unwrapping messages")
  }

  def crunchMinuteFromMessage(cmm: CrunchMinuteMessage): CrunchMinute = CrunchMinute(
    terminal = Terminal(cmm.terminalName.getOrElse("")),
    queue = Queue(cmm.queueName.getOrElse("")),
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
    terminal = Terminal(smm.terminalName.getOrElse("")),
    minute = smm.minute.getOrElse(0L),
    shifts = smm.shifts.getOrElse(0),
    fixedPoints = smm.fixedPoints.getOrElse(0),
    movements = smm.movements.getOrElse(0)
  )

  def flightWithSplitsFromMessage(fm: FlightWithSplitsMessage): ApiFlightWithSplits = ApiFlightWithSplits(
    FlightMessageConversion.flightMessageToApiFlight(fm.flight.get),
    fm.splits.map(sm => splitMessageToApiSplits(sm)).toSet,
    None
  )

  def staffMinuteToMessage(sm: StaffMinute): StaffMinuteMessage = StaffMinuteMessage(
    terminalName = Option(sm.terminal.toString),
    minute = Option(sm.minute),
    shifts = Option(sm.shifts),
    fixedPoints = Option(sm.fixedPoints),
    movements = Option(sm.movements))

  def portStateToSnapshotMessage(portState: PortStateMutable) = CrunchStateSnapshotMessage(
    Option(0L),
    Option(0),
    portState.flights.all.values.toList.map(flight => FlightMessageConversion.flightWithSplitsToMessage(flight)),
    portState.crunchMinutes.all.values.toList.map(crunchMinuteToMessage),
    portState.staffMinutes.all.values.toList.map(staffMinuteToMessage)
  )

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
      sm.eventType,
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
