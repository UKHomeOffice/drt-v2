package services.graphstages

import actors.FlightMessageConversion
import akka.persistence.SnapshotSelectionCriteria
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.{PortStateMinutes, _}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{ActualDeskStats, AirportConfig, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState.{CrunchDiffMessage, CrunchMinuteMessage, CrunchStateSnapshotMessage, StaffMinuteMessage}
import services.SDate
import services.graphstages.Crunch._

case class PortStateWithDiff(portState: PortState, diff: CrunchDiffMessage) {
  def window(start: SDateLike, end: SDateLike): PortStateWithDiff = {
    PortStateWithDiff(portState.window(start, end), crunchDiffWindow(start, end))
  }

  def crunchDiffWindow(start: SDateLike, end: SDateLike): CrunchDiffMessage = {
    val flightsToRemove = diff.flightIdsToRemove.filter(id => portState.flights.get(id).exists(_.hasPcpPaxIn(start, end)))
    val flightsToUpdate = diff.flightsToUpdate.filter(smm => smm.flight.exists(f => start.millisSinceEpoch <= f.scheduled.getOrElse(0L) && f.scheduled.getOrElse(0L) <= end.millisSinceEpoch))
    val staffToUpdate = diff.staffMinutesToUpdate.filter(smm => start.millisSinceEpoch <= smm.minute.getOrElse(0L) && smm.minute.getOrElse(0L) <= end.millisSinceEpoch)
    val crunchToUpdate = diff.crunchMinutesToUpdate.filter(cmm => start.millisSinceEpoch <= cmm.minute.getOrElse(0L) && cmm.minute.getOrElse(0L) <= end.millisSinceEpoch)

    CrunchDiffMessage(Option(SDate.now().millisSinceEpoch), None, flightsToRemove, flightsToUpdate, crunchToUpdate, staffToUpdate)
  }
}

class PortStateGraphStage(name: String = "",
                          optionalInitialPortState: Option[PortState],
                          airportConfig: AirportConfig,
                          expireAfterMillis: MillisSinceEpoch,
                          now: () => SDateLike)
  extends GraphStage[FanInShape6[FlightRemovals, FlightsWithSplits, DeskRecMinutes, ActualDeskStats, StaffMinutes, SimulationMinutes, PortStateWithDiff]] {

  val inFlightRemovals: Inlet[FlightRemovals] = Inlet[FlightRemovals]("FlightRemovals.in")
  val inFlightsWithSplits: Inlet[FlightsWithSplits] = Inlet[FlightsWithSplits]("FlightWithSplits.in")
  val inDeskRecMinutes: Inlet[DeskRecMinutes] = Inlet[DeskRecMinutes]("DeskRecMinutes.in")
  val inActualDeskStats: Inlet[ActualDeskStats] = Inlet[ActualDeskStats]("ActualDeskStats.in")
  val inStaffMinutes: Inlet[StaffMinutes] = Inlet[StaffMinutes]("StaffMinutes.in")
  val inSimulationMinutes: Inlet[SimulationMinutes] = Inlet[SimulationMinutes]("SimulationMinutes.in")
  val outPortState: Outlet[PortStateWithDiff] = Outlet[PortStateWithDiff]("PortStateWithDiff.out")

  override val shape = new FanInShape6(
    inFlightRemovals,
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
              incoming.applyTo(mayBePortState)
          }

          pushIfAppropriate(mayBePortState)

          pull(inlet)
        }
      })
    })

    setHandler(outPortState, new OutHandler {
      override def onPull(): Unit = {
        log.info(s"onPull() called")
        pushIfAppropriate(mayBePortState)

        shape.inlets.foreach(i => if (!hasBeenPulled(i)) pull(i))
      }
    })

    def pushIfAppropriate(maybeState: Option[PortState]): Unit = {
      maybeState match {
        case None => log.info(s"No port state to push yet")
        case Some(portState) if lastMaybePortState.isDefined && lastMaybePortState.get == portState =>
          log.info(s"No updates to push")
        case Some(portState) if !isAvailable(outPortState) =>
          log.info(s"outPortState not available for pushing")
        case Some(portState) =>
          log.info(s"Pushing port state with diff")
          val portStateWithDiff = PortStateWithDiff(portState, diffMessage(lastMaybePortState, portState))
          lastMaybePortState = Option(portState)

          push(outPortState, portStateWithDiff)
      }
    }
  }

  def diffMessage(maybeExistingState: Option[PortState], newState: PortState): CrunchDiffMessage = {
    val existingState = maybeExistingState match {
      case None => PortState(Map(), Map(), Map())
      case Some(s) => s
    }

    val crunchesToUpdate = crunchMinutesDiff(existingState.crunchMinutes, newState.crunchMinutes)
    val staffToUpdate = staffMinutesDiff(existingState.staffMinutes, newState.staffMinutes)
    val (flightsToRemove, flightsToUpdate) = flightsDiff(existingState.flights, newState.flights)
    val diff = CrunchDiff(flightsToRemove, flightsToUpdate, crunchesToUpdate, staffToUpdate)

    CrunchDiffMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      crunchStart = Option(0),
      flightIdsToRemove = diff.flightRemovals.map(rf => rf.flightId).toList,
      flightsToUpdate = diff.flightUpdates.map(FlightMessageConversion.flightWithSplitsToMessage).toList,
      crunchMinutesToUpdate = diff.crunchMinuteUpdates.map(crunchMinuteToMessage).toList,
      staffMinutesToUpdate = diff.staffMinuteUpdates.map(staffMinuteToMessage).toList
    )
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
