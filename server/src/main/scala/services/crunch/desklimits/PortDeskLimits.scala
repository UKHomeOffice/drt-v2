package services.crunch.desklimits

import services.crunch.desklimits.fixed.FixedTerminalDeskLimits
import services.crunch.desklimits.flexed.{FlexedTerminalDeskLimits, FlexedTerminalDeskLimitsFromAvailableStaff}
import uk.gov.homeoffice.drt.egates.EgateBanksUpdates
import uk.gov.homeoffice.drt.ports.Queues.{EGate, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, Queues}
import uk.gov.homeoffice.drt.service.QueueConfig
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.immutable.NumericRange
import scala.concurrent.{ExecutionContext, Future}


object PortDeskLimits {
  def fixed(airportConfig: AirportConfig,
            egatesProvider: Terminal => Future[EgateBanksUpdates],
            paxForQueue: Terminal => (NumericRange[Long], Queue) => Future[Seq[Int]],
           )
           (implicit ec: ExecutionContext): Map[Terminal, FixedTerminalDeskLimits] =
    (
      for {
        terminal <- airportConfig.terminalsForDate(SDate.now().toLocalDate)
        minDesksByQueue24Hrs <- airportConfig.minDesksByTerminalAndQueue24Hrs.get(terminal)
        maxDesksByQueue24Hrs <- airportConfig.maxDesksByTerminalAndQueue24Hrs.get(terminal)
      } yield {
        val queuesForDate = (date: LocalDate) => QueueConfig.queuesForDateAndTerminal(airportConfig.queuesByTerminal)(date, terminal)
        val minDesksForDate = (date: LocalDate) => minDesksByQueue24Hrs.filter { case (queue, _) => queuesForDate(date).contains(queue) }

        (terminal, FixedTerminalDeskLimits(minDesksForDate, capacityProviders(maxDesksByQueue24Hrs, () => egatesProvider(terminal)), paxForQueue(terminal)))
      }
      ).toMap

  def flexed(airportConfig: AirportConfig,
             egatesProvider: Terminal => Future[EgateBanksUpdates],
             paxForQueue: Terminal => (NumericRange[Long], Queue) => Future[Seq[Int]],
            )
            (implicit ec: ExecutionContext): Map[Terminal, FlexedTerminalDeskLimits] =
    airportConfig.desksByTerminal
      .map { case (terminal, terminalDesks) =>
        val queuesForDate = (date: LocalDate) => QueueConfig.queuesForDateAndTerminal(airportConfig.queuesByTerminal)(date, terminal)
        for {
          minDesksByQueue24Hrs <- airportConfig.minDesksByTerminalAndQueue24Hrs.get(terminal)
          maxDesksByQueue24Hrs <- airportConfig.maxDesksByTerminalAndQueue24Hrs.get(terminal)
        } yield {
          val minDesksForDate = (date: LocalDate) => minDesksByQueue24Hrs.filter { case (queue, _) => queuesForDate(date).contains(queue) }
          val limits = FlexedTerminalDeskLimits(
            terminalDesks,
            airportConfig.flexedQueues,
            minDesksForDate,
            capacityProviders(maxDesksByQueue24Hrs, () => egatesProvider(terminal)), paxForQueue(terminal))
          (terminal, limits)
        }
      }
      .collect { case Some(terminalDesks) => terminalDesks }
      .toMap

  def flexedByAvailableStaff(airportConfig: AirportConfig,
                             egatesProvider: Terminal => Future[EgateBanksUpdates],
                             paxForQueue: Terminal => (NumericRange[Long], Queue) => Future[Seq[Int]],
                            )
                            (terminal: Terminal, terminalStaffByMinute: List[Int])
                            (implicit ec: ExecutionContext): FlexedTerminalDeskLimitsFromAvailableStaff = {
    val queuesForDate = (date: LocalDate) => QueueConfig.queuesForDateAndTerminal(airportConfig.queuesByTerminal)(date, terminal)
    val maybeLimits = for {
      minDesksByQueue24Hrs <- airportConfig.minDesksByTerminalAndQueue24Hrs.get(terminal)
      maxDesksByQueue24Hrs <- airportConfig.maxDesksByTerminalAndQueue24Hrs.get(terminal)
      terminalDesks <- airportConfig.desksByTerminal.get(terminal)
    } yield {
      val minDesksForDate = (date: LocalDate) => minDesksByQueue24Hrs.filter { case (queue, _) => queuesForDate(date).contains(queue) }
      FlexedTerminalDeskLimitsFromAvailableStaff(
        terminalStaffByMinute,
        terminalDesks,
        airportConfig.flexedQueues,
        minDesksForDate,
        capacityProviders(maxDesksByQueue24Hrs, () => egatesProvider(terminal)), paxForQueue(terminal))
    }

    maybeLimits.getOrElse(throw new Exception("Failed to create FlexedTerminalDeskLimitsFromAvailableStaff"))
  }

  private def capacityProviders(maxDesks: Map[Queues.Queue, IndexedSeq[Int]], egatesProvider: () => Future[EgateBanksUpdates])
                               (implicit ec: ExecutionContext): Map[Queues.Queue, QueueCapacityProvider] =
    maxDesks.map {
      case (EGate, _) => (EGate, EgatesCapacityProvider(egatesProvider))
      case (nonEgateQueue, max) => (nonEgateQueue, DeskCapacityProvider(max))
    }
}
