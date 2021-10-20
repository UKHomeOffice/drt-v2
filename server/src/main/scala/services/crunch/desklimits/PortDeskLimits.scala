package services.crunch.desklimits

import services.crunch.desklimits.fixed.FixedTerminalDeskLimits
import services.crunch.desklimits.flexed.{FlexedTerminalDeskLimits, FlexedTerminalDeskLimitsFromAvailableStaff}
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.Queues.EGate
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, Queues}

import scala.collection.immutable.Map
import scala.concurrent.{ExecutionContext, Future}


object PortDeskLimits {
  type StaffToDeskLimits = Map[Terminal, List[Int]] => Map[Terminal, FlexedTerminalDeskLimitsFromAvailableStaff]

  def fixed(airportConfig: AirportConfig, egatesProvider: Terminal => Future[EgateBanksUpdates])
           (implicit ec: ExecutionContext): Map[Terminal, FixedTerminalDeskLimits] =
    (
      for {
        terminal <- airportConfig.terminals
        minDesksByQueue24Hrs <- airportConfig.minDesksByTerminalAndQueue24Hrs.get(terminal)
        maxDesksByQueue24Hrs <- airportConfig.maxDesksByTerminalAndQueue24Hrs.get(terminal)
      } yield {
        (terminal, FixedTerminalDeskLimits(minDesksByQueue24Hrs, capacityProviders(maxDesksByQueue24Hrs, () => egatesProvider(terminal))))
      }
      ).toMap

  private def capacityProviders(maxDesks: Map[Queues.Queue, IndexedSeq[Int]], egatesProvider: () => Future[EgateBanksUpdates])
                               (implicit ec: ExecutionContext): Map[Queues.Queue, QueueCapacityProvider] =
    maxDesks.map {
      case (EGate, max) => (EGate, EgatesCapacityProvider(egatesProvider, EgateBank.fromAirportConfig(max)))
      case (nonEgateQueue, max) => (nonEgateQueue, DeskCapacityProvider(max))
    }

  def flexed(airportConfig: AirportConfig, egatesProvider: Terminal => Future[EgateBanksUpdates])
            (implicit ec: ExecutionContext): Map[Terminal, FlexedTerminalDeskLimits] = airportConfig.desksByTerminal
    .mapValues(desks => List.fill(airportConfig.minutesToCrunch)(desks))
    .map { case (terminal, terminalDesksByMinute) =>
      for {
        minDesksByQueue24Hrs <- airportConfig.minDesksByTerminalAndQueue24Hrs.get(terminal)
        maxDesksByQueue24Hrs <- airportConfig.maxDesksByTerminalAndQueue24Hrs.get(terminal)
      } yield {
        val capProviders = capacityProviders(maxDesksByQueue24Hrs, () => egatesProvider(terminal))
        val limits = FlexedTerminalDeskLimits(
          terminalDesksByMinute,
          airportConfig.flexedQueues,
          minDesksByQueue24Hrs,
          capProviders)
        (terminal, limits)
      }
    }
    .collect { case Some(terminalDesks) => terminalDesks }
    .toMap

  def flexedByAvailableStaff(airportConfig: AirportConfig, egatesProvider: Terminal => Future[EgateBanksUpdates])
                            (availableStaffByMinute: Map[Terminal, List[Int]])
                            (implicit ec: ExecutionContext): Map[Terminal, FlexedTerminalDeskLimitsFromAvailableStaff] = {
    val desksByTerminalByMinute = airportConfig.desksByTerminal.mapValues(d => List.fill(airportConfig.minutesToCrunch)(d))

    availableStaffByMinute
      .map { case (terminal, terminalStaffByMinute) =>
        for {
          minDesksByQueue24Hrs <- airportConfig.minDesksByTerminalAndQueue24Hrs.get(terminal)
          maxDesksByQueue24Hrs <- airportConfig.maxDesksByTerminalAndQueue24Hrs.get(terminal)
          terminalDesksByMinute <- desksByTerminalByMinute.get(terminal)
        } yield {
          val limitsFromStaff = FlexedTerminalDeskLimitsFromAvailableStaff(
            terminalStaffByMinute,
            terminalDesksByMinute,
            airportConfig.flexedQueues,
            minDesksByQueue24Hrs,
            capacityProviders(maxDesksByQueue24Hrs, () => egatesProvider(terminal)))
          (terminal, limitsFromStaff)
        }
      }
      .collect { case Some(terminalDesks) => terminalDesks }
      .toMap
  }
}
