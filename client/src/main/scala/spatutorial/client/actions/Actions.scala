package drt.client.actions

import java.util.UUID

import diode.Action
import drt.client.services.{DeskRecTimeslot, Shift}
import drt.shared.{AirportConfig, CrunchResult, SimulationResult, StaffMovement}
import drt.shared.FlightsApi._
import drt.shared.Queues.QueueType

import scala.collection.immutable.{Map, Seq}

object Actions {

  case class ProcessWork(desks: Seq[Double], workload: Seq[Double]) extends Action

  case class UpdateDeskRecsTime(terminalName: TerminalName, queueName: QueueType, item: DeskRecTimeslot) extends Action

  case class UpdateCrunchResult(terminalName: TerminalName, queueName: QueueType, crunchResultWithTimeAndInterval: CrunchResult) extends Action

  case class UpdateSimulationResult(terminalName: TerminalName, queueName: QueueType, simulationResult: SimulationResult) extends Action

  case class UpdateWorkloads(workloads: Map[TerminalName, Map[QueueType, QueuePaxAndWorkLoads]]) extends Action

  case class GetWorkloads(begin: String, end: String) extends Action

  case class GetAirportConfig() extends Action

  case class UpdateAirportConfig(airportConfig: AirportConfig) extends Action

  case class RunAllSimulations() extends Action

  case class RunSimulation(terminalName: TerminalName, queueName: QueueType, desks: List[Int]) extends Action

  case class SetShifts(shifts: String) extends Action

  case class SaveShifts(shifts: String) extends Action

  case class GetShifts() extends Action

  case class AddShift(shift: Shift) extends Action

  case class AddStaffMovement(staffMovement: StaffMovement) extends Action

  case class RemoveStaffMovement(idx: Int, uUID: UUID) extends Action

  case class SaveStaffMovements() extends Action

  case class SetStaffMovements(staffMovements: Seq[StaffMovement]) extends Action

  case class GetStaffMovements() extends Action

}
