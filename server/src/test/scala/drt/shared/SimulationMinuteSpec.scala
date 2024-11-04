package drt.shared

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.model.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.T1

class SimulationMinuteSpec extends Specification {
  val crunchMinute: CrunchMinute = CrunchMinute(
    terminal = T1,
    queue = EeaDesk,
    minute = 0L,
    paxLoad = 1,
    workLoad = 2,
    deskRec = 3,
    waitTime = 4,
    maybePaxInQueue = Option(1),
    deployedDesks = Option(2),
    deployedWait = Option(3),
    maybeDeployedPaxInQueue = Option(4),
    actDesks = Option(5),
    actWait = Option(6),
    lastUpdated = Option(0L))

  "Given a SimulationMinute and a CrunchMinute" >> {
    "We should be able to construct a SimulationMinute from a CrunchMinute" >> {
      SimulationMinute.from(crunchMinute) === SimulationMinute(
        terminal = T1,
        queue = EeaDesk,
        minute = 0L,
        desks = 2,
        waitTime = 3,
      )
    }
    "When they have the same values `maybeUpdated` should return None" >> {
      val simulationMinute = SimulationMinute.from(crunchMinute)

      simulationMinute.maybeUpdated(crunchMinute, 0L) === None
    }
    "When they have different values `maybeUpdated` should return Option with the updated values" >> {
      val updatedCrunchMinute = crunchMinute.copy(deployedDesks = crunchMinute.deployedDesks.map(_ + 1))

      val simulationMinute = SimulationMinute.from(updatedCrunchMinute)

      simulationMinute.maybeUpdated(crunchMinute, 1L) === Option(updatedCrunchMinute.copy(lastUpdated = Option(1L)))
    }
  }
}
