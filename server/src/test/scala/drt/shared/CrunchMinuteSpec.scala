package drt.shared

import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, DeskStat, DeskStatMinute}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.T1

class CrunchMinuteSpec extends Specification {
  "Given a existing CrunchMinute, I should know a new CrunchMinute is an update" >> {
    val paxLoad = 10.0
    val workLoad = 20.0
    val deskRec = 1
    val waitTime = 2
    val maybePaxInQueue = Option(10)
    val nowMillis = 10L
    val deployedDesks = Option(1)
    val deployedWait = Option(2)
    val maybeDeployedPaxInQueue = Option(20)
    val actDesks = Option(1)
    val actWait = Option(2)
    val existing = CrunchMinute(
      terminal = T1,
      queue = EeaDesk,
      minute = 0L,
      paxLoad = paxLoad,
      workLoad = workLoad,
      deskRec = deskRec,
      waitTime = waitTime,
      maybePaxInQueue = maybePaxInQueue,
      deployedDesks = deployedDesks,
      deployedWait = deployedWait,
      maybeDeployedPaxInQueue = maybeDeployedPaxInQueue,
      actDesks = actDesks,
      actWait = actWait,
    )
    "When paxLoad is updated" >> {
      val cm = existing.copy(paxLoad = paxLoad + 1)
      cm.maybeUpdated(existing, nowMillis) === Option(existing.copy(paxLoad = paxLoad + 1, lastUpdated = Option(nowMillis)))
    }
    "When workLoad is updated" >> {
      val cm = existing.copy(workLoad = workLoad + 1)
      cm.maybeUpdated(existing, nowMillis) === Option(existing.copy(workLoad = workLoad + 1.0, lastUpdated = Option(nowMillis)))
    }
    "When deskRec is updated" >> {
      val cm = existing.copy(deskRec = deskRec + 1)
      cm.maybeUpdated(existing, nowMillis) === Option(existing.copy(deskRec = deskRec + 1, lastUpdated = Option(nowMillis)))
    }
    "When waitTime is updated" >> {
      val cm = existing.copy(waitTime = waitTime + 1)
      cm.maybeUpdated(existing, nowMillis) === Option(existing.copy(waitTime = waitTime + 1, lastUpdated = Option(nowMillis)))
    }
    "When maybePaxInQueue is updated" >> {
      val cm = existing.copy(maybePaxInQueue = maybePaxInQueue.map(_ + 1))
      cm.maybeUpdated(existing, nowMillis) === Option(existing.copy(maybePaxInQueue = maybePaxInQueue.map(_ + 1), lastUpdated = Option(nowMillis)))
    }
    "When deployedDesks is updated" >> {
      val cm = existing.copy(deployedDesks = deployedDesks.map(_ + 1))
      cm.maybeUpdated(existing, nowMillis) === Option(existing.copy(deployedDesks = deployedDesks.map(_ + 1), lastUpdated = Option(nowMillis)))
    }
    "When deployedWait is updated" >> {
      val cm = existing.copy(deployedWait = deployedWait.map(_ + 1))
      cm.maybeUpdated(existing, nowMillis) === Option(existing.copy(deployedWait = deployedWait.map(_ + 1), lastUpdated = Option(nowMillis)))
    }
    "When maybeDeployedPaxInQueue is updated" >> {
      val cm = existing.copy(maybeDeployedPaxInQueue = maybeDeployedPaxInQueue.map(_ + 1))
      cm.maybeUpdated(existing, nowMillis) === Option(existing.copy(maybeDeployedPaxInQueue = maybeDeployedPaxInQueue.map(_ + 1), lastUpdated = Option(nowMillis)))
    }
    "When actDesks is updated" >> {
      val cm = existing.copy(actDesks = actDesks.map(_ + 1))
      cm.maybeUpdated(existing, nowMillis) === Option(existing.copy(actDesks = actDesks.map(_ + 1), lastUpdated = Option(nowMillis)))
    }
    "When actWait is updated" >> {
      val cm = existing.copy(actWait = actWait.map(_ + 1))
      cm.maybeUpdated(existing, nowMillis) === Option(existing.copy(actWait = actWait.map(_ + 1), lastUpdated = Option(nowMillis)))
    }
  }

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

  "Given a DeskRecMinute and a CrunchMinute" >> {
    "We should be able to construct a DeskRecMinute from a CrunchMinute" >> {
      DeskRecMinute.from(crunchMinute) === DeskRecMinute(
        terminal = T1,
        queue = EeaDesk,
        minute = 0L,
        paxLoad = 1,
        workLoad = 2,
        deskRec = 3,
        waitTime = 4,
        maybePaxInQueue = Option(1),
      )
    }

    "When they have the same values `maybeUpdated` should return None" >> {
      val deskRecMinute = DeskRecMinute.from(crunchMinute)
      deskRecMinute.maybeUpdated(crunchMinute, 0L) === None
    }
    "When they have different values `maybeUpdated` should return Option with the updated values" >> {
      val updatedCrunchMinute = crunchMinute.copy(deskRec = crunchMinute.deskRec + 1)

      val deskRecMinute = DeskRecMinute.from(updatedCrunchMinute)

      deskRecMinute.maybeUpdated(crunchMinute, 1L) === Option(updatedCrunchMinute.copy(lastUpdated = Option(1L)))
    }
  }
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
  "Given a DeskStatMinute and a CrunchMinute" >> {
    "We should be able to construct a DeskStatMinute from a CrunchMinute" >> {
      DeskStatMinute.from(crunchMinute) === DeskStatMinute(
        terminal = T1,
        queue = EeaDesk,
        minute = 0L,
        deskStat = DeskStat(crunchMinute.actDesks, crunchMinute.actWait)
      )
    }
    "When they have the same values `maybeUpdated` should return None" >> {
      val deskStatMinute = DeskStatMinute.from(crunchMinute)

      deskStatMinute.maybeUpdated(crunchMinute, 0L) === None
    }
    "When they have different values `maybeUpdated` should return Option with the updated values" >> {
      val updatedCrunchMinute = crunchMinute.copy(actDesks = crunchMinute.actDesks.map(_ + 1))

      val deskStatMinute = DeskStatMinute.from(updatedCrunchMinute)

      deskStatMinute.maybeUpdated(crunchMinute, 1L) === Option(updatedCrunchMinute.copy(lastUpdated = Option(1L)))
    }
  }
}
