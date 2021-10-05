package services.crunch.desklimits

import services.crunch.desklimits.fixed.FixedTerminalDeskLimits
import services.crunch.desklimits.flexed.{FlexedTerminalDeskLimits, FlexedTerminalDeskLimitsFromAvailableStaff}
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.collection.immutable.Map


object PortDeskLimits {
  type StaffToDeskLimits = Map[Terminal, List[Int]] => Map[Terminal, FlexedTerminalDeskLimitsFromAvailableStaff]

  def fixed(airportConfig: AirportConfig): Map[Terminal, FixedTerminalDeskLimits] = (
    for {
      terminal <- airportConfig.terminals
      minDesks <- airportConfig.minDesksByTerminalAndQueue24Hrs.get(terminal)
      maxDesks <- airportConfig.maxDesksByTerminalAndQueue24Hrs.get(terminal)
    } yield (terminal, FixedTerminalDeskLimits(minDesks, maxDesks))).toMap

  def flexed(airportConfig: AirportConfig): Map[Terminal, FlexedTerminalDeskLimits] = airportConfig.desksByTerminal
    .mapValues(desks => List.fill(airportConfig.minutesToCrunch)(desks))
    .map { case (terminal, terminalDesksByMinute) =>
      for {
        minDesksByQueue24Hrs <- airportConfig.minDesksByTerminalAndQueue24Hrs.get(terminal)
        maxDesksByQueue24Hrs <- airportConfig.maxDesksByTerminalAndQueue24Hrs.get(terminal)
      } yield (terminal, FlexedTerminalDeskLimits(terminalDesksByMinute, airportConfig.flexedQueues, minDesksByQueue24Hrs, maxDesksByQueue24Hrs))
    }
    .collect { case Some(terminalDesks) => terminalDesks }
    .toMap

  def flexedByAvailableStaff(airportConfig: AirportConfig)
                            (availableStaffByMinute: Map[Terminal, List[Int]]): Map[Terminal, FlexedTerminalDeskLimitsFromAvailableStaff] = {
    val desksByTerminalByMinute = airportConfig.desksByTerminal.mapValues(d => List.fill(airportConfig.minutesToCrunch)(d))

    availableStaffByMinute
      .map { case (terminal, terminalStaffByMinute) =>
        for {
          minDesksByQueue24Hrs <- airportConfig.minDesksByTerminalAndQueue24Hrs.get(terminal)
          maxDesksByQueue24Hrs <- airportConfig.maxDesksByTerminalAndQueue24Hrs.get(terminal)
          terminalDesksByMinute <- desksByTerminalByMinute.get(terminal)
        } yield {

          (terminal, FlexedTerminalDeskLimitsFromAvailableStaff(terminalStaffByMinute, terminalDesksByMinute, airportConfig.flexedQueues, minDesksByQueue24Hrs, maxDesksByQueue24Hrs))
        }
      }
      .collect { case Some(terminalDesks) => terminalDesks }
      .toMap
  }
}
