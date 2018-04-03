package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.{PortStateMinutes, _}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{ActualDeskStats, AirportConfig, SDateLike}
import org.slf4j.{Logger, LoggerFactory}

class PortStateGraphStage(name: String = "",
                          optionalInitialPortState: Option[PortState],
                          airportConfig: AirportConfig,
                          expireAfterMillis: MillisSinceEpoch,
                          now: () => SDateLike)
  extends GraphStage[FanInShape6[FlightRemovals, FlightsWithSplits, DeskRecMinutes, ActualDeskStats, StaffMinutes, SimulationMinutes, PortState]] {

  val inFlightRemovals: Inlet[FlightRemovals] = Inlet[FlightRemovals]("FlightRemovals.in")
  val inFlightsWithSplits: Inlet[FlightsWithSplits] = Inlet[FlightsWithSplits]("FlightWithSplits.in")
  val inDeskRecMinutes: Inlet[DeskRecMinutes] = Inlet[DeskRecMinutes]("DeskRecMinutes.in")
  val inActualDeskStats: Inlet[ActualDeskStats] = Inlet[ActualDeskStats]("ActualDeskStats.in")
  val inStaffMinutes: Inlet[StaffMinutes] = Inlet[StaffMinutes]("StaffMinutes.in")
  val inSimulationMinutes: Inlet[SimulationMinutes] = Inlet[SimulationMinutes]("SimulationMinutes.in")
  val outPortState: Outlet[PortState] = Outlet[PortState]("outPortState.out")

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
    var mayBePortState: Option[PortState] = None
    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      super.preStart()
    }

    shape.inlets.foreach(inlet => {
      setHandler(inlet, new InHandler {
        override def onPush(): Unit = {
          mayBePortState = grab(inlet) match {
            case incoming: PortStateMinutes => incoming.applyTo(mayBePortState)
          }

          pull(inlet)
        }
      })
    })

    setHandler(outPortState, new OutHandler {
      override def onPull(): Unit = {
        mayBePortState.foreach(portState => push(outPortState, portState))
        shape.inlets.foreach(i => if (!hasBeenPulled(i)) pull(i))
      }
    })
  }
}