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
import scala.collection.immutable.{Map, SortedMap}

case class PortStateWithDiff(portState: PortState, diff: PortStateDiff, diffMessage: CrunchDiffMessage) {
  def window(start: SDateLike, end: SDateLike, portQueues: Map[TerminalName, Seq[QueueName]]): PortStateWithDiff = {
    PortStateWithDiff(portState.window(start, end, portQueues), diff.window(start, end, portQueues), crunchDiffWindow(start, end))
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
    var maybePortState: Option[PortState] = None
    var maybePortStateDiff: Option[PortStateDiff] = None
    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      log.info(s"Received initial port state")
      maybePortState = optionalInitialPortState
      super.preStart()
    }

    shape.inlets.foreach(inlet => {
      setHandler(inlet, new InHandler {
        override def onPush(): Unit = {
          val (updatedState, stateDiff) = grab(inlet) match {
            case incoming: PortStateMinutes =>
              log.info(s"Incoming ${inlet.toString}")
              val startTime = now().millisSinceEpoch
              val expireThreshold = now().addMillis(-1 * expireAfterMillis.toInt).millisSinceEpoch
              val (newState, diff) = incoming
                .applyTo(maybePortState, startTime)
              val elapsedSeconds = (now().millisSinceEpoch - startTime).toDouble / 1000
              log.info(f"Finished processing $inlet data in $elapsedSeconds%.2f seconds")
              (newState.purgeOlderThanDate(expireThreshold), diff)
          }

          maybePortState = Option(updatedState)
          maybePortStateDiff = mergeDiff(maybePortStateDiff, stateDiff)

          pushIfAppropriate()

          pullAllInlets()
        }
      })
    })

    setHandler(outPortState, new OutHandler {
      override def onPull(): Unit = {
        val start = now()
        log.info(s"onPull() called")
        pushIfAppropriate()

        pullAllInlets()
        log.info(s"outPortState Took ${now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def mergeDiff(maybePortStateDiff: Option[PortStateDiff], newDiff: PortStateDiff): Option[PortStateDiff] = maybePortStateDiff match {
      case None => Option(newDiff)
      case Some(existingDiff) =>
        Option(existingDiff.copy(
          flightRemovals = newDiff.flightRemovals ++ existingDiff.flightRemovals,
          flightUpdates = existingDiff.flightUpdates ++ newDiff.flightUpdates,
          crunchMinuteUpdates = existingDiff.crunchMinuteUpdates ++ newDiff.crunchMinuteUpdates,
          staffMinuteUpdates = existingDiff.staffMinuteUpdates ++ newDiff.staffMinuteUpdates))
    }

    def pullAllInlets(): Unit = {
      shape.inlets.foreach(i => if (!hasBeenPulled(i)) {
        log.info(s"Pulling $i")
        pull(i)
      })
    }

    def pushIfAppropriate(): Unit = {
      maybePortStateDiff match {
        case None => log.info(s"No port state diff to push yet")
        case Some(_) if !isAvailable(outPortState) =>
          log.info(s"outPortState not available for pushing")
        case Some(portStateDiff) =>
          log.info(s"Pushing port state with diff")
          maybePortState match {
            case None =>
            case Some(portState) =>
              val portStateWithDiff = PortStateWithDiff(PortState.empty, portStateDiff, diffMessage(portStateDiff))
              maybePortStateDiff = None
              push(outPortState, portStateWithDiff)
          }
      }
    }
  }

  def diffMessage(diff: PortStateDiff): CrunchDiffMessage = CrunchDiffMessage(
    createdAt = Option(now().millisSinceEpoch),
    crunchStart = Option(0),
    flightIdsToRemove = diff.flightRemovals.map(_.flightKey.uniqueId),
    flightsToUpdate = diff.flightUpdates.values.map(FlightMessageConversion.flightWithSplitsToMessage).toList,
    crunchMinutesToUpdate = diff.crunchMinuteUpdates.values.map(crunchMinuteToMessage).toList,
    staffMinutesToUpdate = diff.staffMinuteUpdates.values.map(staffMinuteToMessage).toList
  )

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
