package actors.serializers

import actors.serializers.FlightMessageConversion.flightWithSplitsFromMessage
import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState._
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxAge, PaxType}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitSource, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

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

  def flightsFromMessages(flightMessages: Seq[FlightWithSplitsMessage]): Map[UniqueArrival, ApiFlightWithSplits] =
    flightMessages.map(message => {
      val fws = flightWithSplitsFromMessage(message)
      (fws.unique, fws)
    }).toMap

  def portStateToSnapshotMessage(portState: PortState): CrunchStateSnapshotMessage = CrunchStateSnapshotMessage(
    Option(0L),
    Option(0),
    portState.flights.values.toList.map(flight => FlightMessageConversion.flightWithSplitsToMessage(flight)),
    portState.crunchMinutes.values.toList.map(crunchMinuteToMessage),
    portState.staffMinutes.values.toList.map(staffMinuteToMessage)
  )

  def splitMessageToApiSplits(sm: SplitMessage): Splits = {
    val splitSource = SplitSource(sm.source.getOrElse("")) match {
      case SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages_Old => SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
      case s => s
    }

    Splits(
      sm.paxTypeAndQueueCount.map(ptqcm => {
        ApiPaxTypeAndQueueCount(
          PaxType(ptqcm.paxType.getOrElse("")),
          Queue(ptqcm.queueType.getOrElse("")),
          ptqcm.paxValue.getOrElse(0d),
          nationalitiesFromMessage(ptqcm),
          passengerAgesFromMessage(ptqcm)
        )
      }).toSet,
      splitSource,
      sm.eventType.map(EventType(_)),
      SplitStyle(sm.style.getOrElse("")),
    )
  }

  def nationalitiesFromMessage(ptqcm: PaxTypeAndQueueCountMessage): Option[Map[Nationality, Double]] = ptqcm
    .nationalities
    .map(nc => {
      nc.paxNationality -> nc.count
    }).collect {
    case (Some(nat), Some(count)) => Nationality(nat) -> count
  }.toMap match {
    case nats if nats.isEmpty => None
    case nats => Option(nats)
  }

  def passengerAgesFromMessage(ptqcm: PaxTypeAndQueueCountMessage): Option[Map[PaxAge, Double]] = ptqcm
    .ages
    .map(pa => {
      pa.paxAge -> pa.count
    }).collect {
    case (Some(age), Some(count)) => PaxAge(age) -> count
  }.toMap match {
    case ages if ages.isEmpty => None
    case ages => Option(ages)
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
    actWait = cm.actWait,
    lastUpdated = cm.lastUpdated
  )
}
