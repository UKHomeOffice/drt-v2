package drt.client.actions

import java.util.UUID

import diode.Action
import drt.client.services.{DeskRecTimeslot, StaffAssignment}
import drt.shared._
import drt.shared.FlightsApi._
import drt.shared.Simulations.QueueSimulationResult

import scala.collection.immutable.{Map, Seq}

object Actions {

  case class ProcessWork(desks: Seq[Double], workload: Seq[Double]) extends Action

  case class UpdateDeskRecsTime(terminalName: TerminalName, queueName: QueueName, item: DeskRecTimeslot) extends Action

  case class UpdateCrunchResult(terminalName: TerminalName, queueName: QueueName, crunchResultWithTimeAndInterval: CrunchResult) extends Action

  case class UpdateSimulationResult(terminalName: TerminalName, queueName: QueueName, simulationResult: QueueSimulationResult) extends Action

  case class UpdateWorkloads(workloads: TerminalQueuePaxAndWorkLoads[QueuePaxAndWorkLoads]) extends Action

  case class GetWorkloads(begin: String, end: String) extends Action

  case class GetAirportConfig() extends Action

  case class UpdateAirportConfig(airportConfig: AirportConfig) extends Action

  case class RunAllSimulations() extends Action

  case class RunTerminalSimulations(terminalName: TerminalName) extends Action

  case class RunSimulation(terminalName: TerminalName, queueName: QueueName, desks: List[Int]) extends Action

  case class SetFixedPoints(fixedPoints: String, terminalName: Option[String]) extends Action

  case class SaveFixedPoints(fixedPoints: String, terminalName: TerminalName) extends Action

  case class GetFixedPoints() extends Action

  case class SetShifts(shifts: String) extends Action

  case class SaveShifts(shifts: String) extends Action

  case class GetShifts() extends Action

  case class AddShift(shift: StaffAssignment) extends Action

  case class AddStaffMovement(staffMovement: StaffMovement) extends Action

  case class RemoveStaffMovement(idx: Int, uUID: UUID) extends Action

  case class SaveStaffMovements(terminalName: TerminalName) extends Action

  case class SetStaffMovements(staffMovements: Seq[StaffMovement]) extends Action

  case class GetStaffMovements() extends Action

  case class GetActualDeskStats() extends Action

  case class SetActualDeskStats(desks: Map[String, Map[String, Map[Long, DeskStat]]]) extends Action

}
