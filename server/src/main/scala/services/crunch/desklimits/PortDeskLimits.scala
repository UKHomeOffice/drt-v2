package services.crunch.desklimits

import services.crunch.desklimits.fixed.FixedTerminalDeskLimits
import services.crunch.desklimits.flexed.{FlexedTerminalDeskLimits, FlexedTerminalDeskLimitsFromAvailableStaff}
import uk.gov.homeoffice.drt.egates.EgateBanksUpdates
import uk.gov.homeoffice.drt.ports.Queues.EGate
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, Queues}

import scala.concurrent.{ExecutionContext, Future}


object PortDeskLimits {
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
      case (EGate, _) => (EGate, EgatesCapacityProvider(egatesProvider))
      case (nonEgateQueue, max) => (nonEgateQueue, DeskCapacityProvider(max))
    }

  def flexed(airportConfig: AirportConfig, egatesProvider: Terminal => Future[EgateBanksUpdates])
            (implicit ec: ExecutionContext): Map[Terminal, FlexedTerminalDeskLimits] = airportConfig.desksByTerminal
    .map { case (terminal, terminalDesks) =>
      for {
        minDesksByQueue24Hrs <- airportConfig.minDesksByTerminalAndQueue24Hrs.get(terminal)
        maxDesksByQueue24Hrs <- airportConfig.maxDesksByTerminalAndQueue24Hrs.get(terminal)
      } yield {
        val limits = FlexedTerminalDeskLimits(
          terminalDesks,
          airportConfig.flexedQueues,
          minDesksByQueue24Hrs,
          capacityProviders(maxDesksByQueue24Hrs, () => egatesProvider(terminal)))
        (terminal, limits)
      }
    }
    .collect { case Some(terminalDesks) => terminalDesks }
    .toMap

  def flexedByAvailableStaff(airportConfig: AirportConfig, egatesProvider: Terminal => Future[EgateBanksUpdates])
                            (terminal: Terminal, terminalStaffByMinute: List[Int])
                            (implicit ec: ExecutionContext): FlexedTerminalDeskLimitsFromAvailableStaff = {
    val maybeLimits = for {
      minDesksByQueue24Hrs <- airportConfig.minDesksByTerminalAndQueue24Hrs.get(terminal)
      maxDesksByQueue24Hrs <- airportConfig.maxDesksByTerminalAndQueue24Hrs.get(terminal)
      terminalDesksByMinute <- airportConfig.desksByTerminal.get(terminal)
    } yield {
      FlexedTerminalDeskLimitsFromAvailableStaff(
        terminalStaffByMinute,
        terminalDesksByMinute,
        airportConfig.flexedQueues,
        minDesksByQueue24Hrs,
        capacityProviders(maxDesksByQueue24Hrs, () => egatesProvider(terminal)))
    }

    maybeLimits.getOrElse(throw new Exception("Failed to create FlexedTerminalDeskLimitsFromAvailableStaff"))
  }
}
