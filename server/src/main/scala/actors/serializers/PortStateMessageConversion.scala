package actors.serializers

import drt.shared.CrunchApi.StaffMinute
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.model.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState._
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion.flightWithSplitsFromMessage
import uk.gov.homeoffice.drt.time.SDateLike

object PortStateMessageConversion {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def snapshotMessageToState(sm: CrunchStateSnapshotMessage,
                             optionalTimeWindowEnd: Option[SDateLike]): PortState =
    optionalTimeWindowEnd match {
      case None =>
        val flights = sm.flightWithSplits.map(flightWithSplitsFromMessage)
        val crunchMinutes = sm.crunchMinutes.map(crunchMinuteFromMessage)
        val staffMinutes = sm.staffMinutes.map(staffMinuteFromMessage)
        PortState(flights, crunchMinutes, staffMinutes)
      case Some(timeWindowEnd) =>
        val windowEndMillis = timeWindowEnd.millisSinceEpoch
        val flights = sm.flightWithSplits.collect {
          case message if message.flight.map(fm => fm.pcpTime.getOrElse(0L)).getOrElse(0L) <= windowEndMillis =>
            flightWithSplitsFromMessage(message)
        }
        val crunchMinutes = sm.crunchMinutes.collect {
          case message if message.getMinute <= windowEndMillis =>
            crunchMinuteFromMessage(message)
        }
        val staffMinutes = sm.staffMinutes.collect {
          case message if message.getMinute <= windowEndMillis =>
            staffMinuteFromMessage(message)
        }
        PortState(flights, crunchMinutes, staffMinutes)
    }

  def crunchMinuteFromMessage(cmm: CrunchMinuteMessage): CrunchMinute = {
    val minute = cmm.minute.getOrElse(0L)
    val roundedMinute: Long = minute - (minute % 60000)

    CrunchMinute(
      terminal = Terminal(cmm.terminalName.getOrElse("")),
      queue = Queue(cmm.queueName.getOrElse("")),
      minute = roundedMinute,
      paxLoad = cmm.paxLoad.getOrElse(0d),
      workLoad = cmm.workLoad.getOrElse(0d),
      deskRec = cmm.deskRec.getOrElse(0),
      waitTime = cmm.waitTime.getOrElse(0),
      maybePaxInQueue = cmm.maybePaxInQueue,
      deployedDesks = cmm.simDesks,
      deployedWait = cmm.simWait,
      maybeDeployedPaxInQueue = cmm.maybeDeployedPaxInQueue,
      actDesks = cmm.actDesks,
      actWait = cmm.actWait,
      lastUpdated = cmm.lastUpdated
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
      movements = smm.movements.getOrElse(0),
      lastUpdated = smm.lastUpdated
    )
  }

  def staffMinuteToMessage(sm: StaffMinute): StaffMinuteMessage = StaffMinuteMessage(
    terminalName = Option(sm.terminal.toString),
    minute = Option(sm.minute),
    shifts = Option(sm.shifts),
    fixedPoints = Option(sm.fixedPoints),
    movements = Option(sm.movements),
    lastUpdated = sm.lastUpdated)

  def crunchMinuteToMessage(cm: CrunchMinute): CrunchMinuteMessage = CrunchMinuteMessage(
    terminalName = Option(cm.terminal.toString),
    queueName = Option(cm.queue.toString),
    minute = Option(cm.minute),
    paxLoad = Option(cm.paxLoad),
    workLoad = Option(cm.workLoad),
    deskRec = Option(cm.deskRec),
    waitTime = Option(cm.waitTime),
    maybePaxInQueue = cm.maybePaxInQueue,
    simDesks = cm.deployedDesks,
    simWait = cm.deployedWait,
    actDesks = cm.actDesks,
    actWait = cm.actWait,
    maybeDeployedPaxInQueue = cm.maybeDeployedPaxInQueue,
    lastUpdated = cm.lastUpdated
  )
}
