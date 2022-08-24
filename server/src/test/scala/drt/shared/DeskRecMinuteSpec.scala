package drt.shared

import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.T1

class DeskRecMinuteSpec extends Specification {
  "Given a CrunchMinute, I should know the DeskRecMinute is an update" >> {
    val paxLoad = 10.0
    val workLoad = 20.0
    val deskRec = 1
    val waitTime = 2
    val maybePaxInQueue = Option(10)
    val nowMillis = 10L
    val cm = CrunchMinute(T1, EeaDesk, 0L, paxLoad, workLoad, deskRec, waitTime, maybePaxInQueue)
    "When paxLoad is updated" >> {
      val drm = DeskRecMinute(T1, EeaDesk, 0L, paxLoad + 1, workLoad, deskRec, waitTime, maybePaxInQueue)
      drm.maybeUpdated(cm, nowMillis) === Option(cm.copy(paxLoad = paxLoad + 1, lastUpdated = Option(nowMillis)))
    }
    "When workLoad is updated" >> {
      val drm = DeskRecMinute(T1, EeaDesk, 0L, paxLoad, workLoad + 1.0, deskRec, waitTime, maybePaxInQueue)
      drm.maybeUpdated(cm, nowMillis) === Option(cm.copy(workLoad = workLoad + 1.0, lastUpdated = Option(nowMillis)))
    }
    "When deskRec is updated" >> {
      val drm = DeskRecMinute(T1, EeaDesk, 0L, paxLoad, workLoad, deskRec + 1, waitTime, maybePaxInQueue)
      drm.maybeUpdated(cm, nowMillis) === Option(cm.copy(deskRec = deskRec + 1, lastUpdated = Option(nowMillis)))
    }
    "When waitTime is updated" >> {
      val drm = DeskRecMinute(T1, EeaDesk, 0L, paxLoad, workLoad, deskRec, waitTime + 1, maybePaxInQueue)
      drm.maybeUpdated(cm, nowMillis) === Option(cm.copy(waitTime = waitTime + 1, lastUpdated = Option(nowMillis)))
    }
    "When maybePaxInQueue is updated" >> {
      val drm = DeskRecMinute(T1, EeaDesk, 0L, paxLoad, workLoad, deskRec, waitTime, maybePaxInQueue.map(_ + 1))
      drm.maybeUpdated(cm, nowMillis) === Option(cm.copy(maybePaxInQueue = maybePaxInQueue.map(_ + 1), lastUpdated = Option(nowMillis)))
    }
  }
}
