package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{ActualDeskStats, AirportConfig, SDateLike}
import org.slf4j.{Logger, LoggerFactory}

class PortStateGraphStage(name: String = "",
                          optionalInitialPortState: Option[PortState],
                          airportConfig: AirportConfig,
                          expireAfterMillis: MillisSinceEpoch,
                          now: () => SDateLike)
  extends GraphStage[FanInShape6[Set[Int], FlightsWithSplits, DeskRecMinutes, ActualDeskStats, StaffMinutes, SimulationMinutes, PortState]] {

  val inFlightRemovals: Inlet[Set[Int]] = Inlet[Set[Int]]("FlightRemovals.in")
  val inFlightWithSplits: Inlet[FlightsWithSplits] = Inlet[FlightsWithSplits]("FlightWithSplits.in")
  val inDeskRecMinutes: Inlet[DeskRecMinutes] = Inlet[DeskRecMinutes]("DeskRecMinutes.in")
  val inActualDeskStats: Inlet[ActualDeskStats] = Inlet[ActualDeskStats]("ActualDeskStats.in")
  val inStaffMinutes: Inlet[StaffMinutes] = Inlet[StaffMinutes]("StaffMinutes.in")
  val inSimulationMinutes: Inlet[SimulationMinutes] = Inlet[SimulationMinutes]("SimulationMinutes.in")
  val outDeskRecMinutes: Outlet[PortState] = Outlet[PortState]("outPortState.out")

  override val shape = new FanInShape6(
    inFlightRemovals,
    inFlightWithSplits,
    inDeskRecMinutes,
    inActualDeskStats,
    inStaffMinutes,
    inSimulationMinutes,
    outDeskRecMinutes
  )

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var mayBePortState: Option[PortState] = None
    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      super.preStart()
    }

    setHandler(inFlightRemovals, new InHandler {
      override def onPush(): Unit = ???
    }
  }
}