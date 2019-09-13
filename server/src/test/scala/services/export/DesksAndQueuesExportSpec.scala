package services.export

import drt.shared.CrunchApi.CrunchMinute
import drt.shared.Queues
import org.specs2.mutable.SpecificationLike
import services.SDate

class DesksAndQueuesExportSpec extends SpecificationLike {
  val terminalQueues = Map("T1" -> Seq(Queues.EeaDesk, Queues.NonEeaDesk))
  "Given one crunch minute for a terminal " +
    "When asking for an hour's worth of 15 summaries by queue " +
    "I should see numbers from the crunch minute, and zeros where there was no data" >> {
    val terminal = "T1"
    val noon05 = SDate("2019-01-01T12:05:00")
    val summaryStart = SDate("2019-01-01T12:00")
    val summaryHours = 1
    val summaryPeriodMinutes = 15
    val cmNoon05 = CrunchMinute(terminal, Queues.EeaDesk, noon05.millisSinceEpoch, 5, 6, 7, 8, Option(9), Option(10), Option(11), Option(12))

    for {
      queue <- terminalQueues(terminal)
      summaryPeriodStart <- summaryStart.millisSinceEpoch until summaryStart.addHours(summaryHours).millisSinceEpoch by (summaryPeriodMinutes * 60000L)
    } yield {

    }

  }

//  "Given some crunch minutes for a terminal " +
//    "When asking for a 15 summary by queue " +
//    "I should see appropriate numbers from the crunch minutes, and blanks where there was no data" >> {
//    val noon05 = SDate("2019-01-01T12:05:00")
//    val noon20 = SDate("2019-01-01T12:20:00")
//    val cmNoon05 = CrunchMinute("T1", Queues.EeaDesk, noon05.millisSinceEpoch, 5, 6, 7, 8, Option(9), Option(10), Option(11), Option(12))
//    val cmNoon02 = incCmNos(cmNoon05).copy(minute = noon05.addMinutes(-1).millisSinceEpoch)
//  }

  def incCmNos(cm: CrunchMinute): CrunchMinute = cm.copy(
    paxLoad = cm.paxLoad + 1,
    workLoad = cm.workLoad + 1,
    deskRec = cm.deskRec+ 1,
    waitTime = cm.waitTime + 1,
    deployedDesks = cm.deployedDesks.map(_ + 1),
    deployedWait = cm.deployedWait.map(_ + 1),
    actDesks = cm.actDesks.map(_ + 1),
    actWait = cm.actWait.map(_ + 1)
  )
}
