package drt.shared

import drt.shared.CrunchApi.CrunchMinute
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.T1

class CrunchMinuteSpec extends Specification {
  "Given a existing CrunchMinute, I should know a new CrunchMinute is an update" >> {
    val paxLoad = 10.0
    val maybePassengers = Option(Seq(1.0, 2.0))
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
}
