package services.graphstages

import actors.FlightMessageConversion
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState.{CrunchDiffMessage, CrunchMinuteMessage, StaffMinuteMessage}
import services.SDate
import services.graphstages.Crunch._

import scala.collection.immutable

case class PortStateWithDiff(portState: PortState, diff: PortStateDiff, diffMessage: CrunchDiffMessage) {
  def window(start: SDateLike, end: SDateLike): PortStateWithDiff = {
    PortStateWithDiff(portState.window(start, end), diff, crunchDiffWindow(start, end))
  }

  def crunchDiffWindow(start: SDateLike, end: SDateLike): CrunchDiffMessage = {
    val flightsToRemove = diffMessage.flightIdsToRemove
    val flightsToUpdate = diffMessage.flightsToUpdate.filter(smm => smm.flight.exists(f => start.millisSinceEpoch <= f.scheduled.getOrElse(0L) && f.scheduled.getOrElse(0L) <= end.millisSinceEpoch))
    val staffToUpdate = diffMessage.staffMinutesToUpdate.filter(smm => start.millisSinceEpoch <= smm.minute.getOrElse(0L) && smm.minute.getOrElse(0L) <= end.millisSinceEpoch)
    val crunchToUpdate = diffMessage.crunchMinutesToUpdate.filter(cmm => start.millisSinceEpoch <= cmm.minute.getOrElse(0L) && cmm.minute.getOrElse(0L) <= end.millisSinceEpoch)

    CrunchDiffMessage(Option(SDate.now().millisSinceEpoch), None, flightsToRemove, flightsToUpdate, crunchToUpdate, staffToUpdate)
  }
}

class PortStateGraphStage(name: String = "",
                          optionalInitialPortState: Option[PortState],
                          airportConfig: AirportConfig,
                          expireAfterMillis: MillisSinceEpoch,
                          now: () => SDateLike)
  extends GraphStage[FanInShape5[FlightsWithSplits, DeskRecMinutes, ActualDeskStats, StaffMinutes, SimulationMinutes, PortStateWithDiff]] {

  val inFlightsWithSplits: Inlet[FlightsWithSplits] = Inlet[FlightsWithSplits]("FlightWithSplits.in")
  val inDeskRecMinutes: Inlet[DeskRecMinutes] = Inlet[DeskRecMinutes]("DeskRecMinutes.in")
  val inActualDeskStats: Inlet[ActualDeskStats] = Inlet[ActualDeskStats]("ActualDeskStats.in")
  val inStaffMinutes: Inlet[StaffMinutes] = Inlet[StaffMinutes]("StaffMinutes.in")
  val inSimulationMinutes: Inlet[SimulationMinutes] = Inlet[SimulationMinutes]("SimulationMinutes.in")
  val outPortState: Outlet[PortStateWithDiff] = Outlet[PortStateWithDiff]("PortStateWithDiff.out")

  override val shape = new FanInShape5(
    inFlightsWithSplits,
    inDeskRecMinutes,
    inActualDeskStats,
    inStaffMinutes,
    inSimulationMinutes,
    outPortState
  )

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var lastMaybePortState: Option[PortState] = None
    var mayBePortState: Option[PortState] = None
    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      log.info(s"Received initial port state")
      lastMaybePortState = optionalInitialPortState
      mayBePortState = lastMaybePortState
      super.preStart()
    }

    shape.inlets.foreach(inlet => {
      setHandler(inlet, new InHandler {
        override def onPush(): Unit = {
          mayBePortState = grab(inlet) match {
            case incoming: PortStateMinutes =>
              log.info(s"Incoming ${inlet.toString}")
              val startTime = SDate.now().millisSinceEpoch
              val newState = incoming.applyTo(mayBePortState, now())
              val elapsedSeconds = (SDate.now().millisSinceEpoch - startTime).toDouble / 1000
              log.info(f"Finished processing $inlet data in $elapsedSeconds%.2f seconds")
              newState
          }

          pushIfAppropriate(mayBePortState)

          pullAllInlets()
        }
      })
    })

    setHandler(outPortState, new OutHandler {
      override def onPull(): Unit = {
        val start = SDate.now()
        log.info(s"onPull() called")
        pushIfAppropriate(mayBePortState)

        pullAllInlets()
        log.info(s"outPortState Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def pullAllInlets(): Unit = {
      shape.inlets.foreach(i => if (!hasBeenPulled(i)) {
        log.info(s"Pulling $i")
        pull(i)
      })
    }

    def pushIfAppropriate(maybeState: Option[PortState]): Unit = {
      maybeState match {
        case None => log.info(s"No port state to push yet")
        case Some(portState) if lastMaybePortState.isDefined && lastMaybePortState.get == portState =>
          log.info(s"No updates to push")
        case Some(_) if !isAvailable(outPortState) =>
          log.info(s"outPortState not available for pushing")
        case Some(portState) =>
          log.info(s"Pushing port state with diff")
          val diff: PortStateDiff = stateDiff(lastMaybePortState, portState)
//          log.info(s"diff: ${diff.staffMinuteUpdates.find(_.minute == SDate("2019-03-25T00:00").millisSinceEpoch).map(_.movements).getOrElse("--")}")
          val portStateWithDiff = PortStateWithDiff(portState, diff, diffMessage(diff))
          lastMaybePortState = Option(portState)

          push(outPortState, portStateWithDiff)
      }
    }
  }

  def diffMessage(diff: PortStateDiff): CrunchDiffMessage = CrunchDiffMessage(
    createdAt = Option(SDate.now().millisSinceEpoch),
    crunchStart = Option(0),
    flightIdsToRemove = diff.flightRemovals.map(_.flightKey.uniqueId).toList,
    flightsToUpdate = diff.flightUpdates.map(FlightMessageConversion.flightWithSplitsToMessage).toList,
    crunchMinutesToUpdate = diff.crunchMinuteUpdates.map(crunchMinuteToMessage).toList,
    staffMinutesToUpdate = diff.staffMinuteUpdates.map(staffMinuteToMessage).toList
  )

  def stateDiff(maybeExistingState: Option[PortState], newState: PortState): PortStateDiff = {
    val existingState = maybeExistingState match {
      case None => PortState(Map(), Map(), Map())
      case Some(s) => s
    }

    val crunchesToUpdate = crunchMinutesDiff(existingState.crunchMinutes, newState.crunchMinutes)
    val staffToUpdate = staffMinutesDiff(existingState.staffMinutes, newState.staffMinutes)
    val (flightsToRemove, flightsToUpdate) = flightsDiff(existingState.flights, newState.flights)

    PortStateDiff(flightsToRemove, flightsToUpdate, crunchesToUpdate, staffToUpdate)
  }

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
}

case class DeskStat(desks: Option[Int], waitTime: Option[Int])

case class ActualDeskStats(portDeskSlots: Map[String, Map[String, Map[MillisSinceEpoch, DeskStat]]]) extends PortStateMinutes {
  def applyTo(maybePortState: Option[PortState], now: SDateLike): Option[PortState] = {
    val flattened = portDeskSlots
      .flatMap {
        case (tn, queueDeskSlots) =>
          queueDeskSlots.flatMap {
            case (qn, minuteDesks) =>
              minuteDesks.map {
                case (millis, deskStat) =>
                  (tn, qn, millis, deskStat)
              }
          }
      }
      .toSeq

    maybePortState.map(portState => {
      flattened.foldLeft(portState) {
        case (portStateSoFar, (tn, qn, millis, deskStat)) => expandSlotAndApply(tn, qn, portStateSoFar, millis, deskStat)
      }
    })
  }

  def expandSlotAndApply(terminalName: TerminalName, queueName: QueueName, portState: PortState, millis: MillisSinceEpoch, deskStat: DeskStat): PortState = {
    (millis until millis + 15 * oneMinuteMillis by oneMinuteMillis).foldLeft(portState) {
      case (portStateToUpdate, minuteMillis) =>
        val key = MinuteHelper.key(terminalName, queueName, minuteMillis)
        portStateToUpdate.crunchMinutes.get(key) match {
          case None => portStateToUpdate
          case Some(cm) if cm.actDesks == deskStat.desks && cm.actWait == deskStat.waitTime => portStateToUpdate
          case Some(cm) =>
            val cmWithDeskStats = cm.copy(actDesks = deskStat.desks, actWait = deskStat.waitTime, lastUpdated = Option(SDate.now().millisSinceEpoch))
            val updatedCms = portStateToUpdate.crunchMinutes.updated(key, cmWithDeskStats)
            portStateToUpdate.copy(crunchMinutes = updatedCms)
        }
    }
  }

  def crunchMinutesWithActDesks(queueActDesks: Map[MillisSinceEpoch, DeskStat], crunchMinutes: Map[Int, CrunchMinute], terminalName: TerminalName, queueName: QueueName): immutable.Iterable[CrunchMinute] = {
    queueActDesks
      .map {
        case (millis, deskStat) =>
          crunchMinutes
            .values
            .find(cm => cm.minute == millis && cm.queueName == queueName && cm.terminalName == terminalName)
            .map(cm => cm.copy(actDesks = deskStat.desks, actWait = deskStat.waitTime))
      }
      .collect {
        case Some(cm) => cm
      }
  }
}
