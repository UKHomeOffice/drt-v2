package services.export

import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}
import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
import org.specs2.mutable.SpecificationLike
import services.SDate

import scala.collection.mutable

class DesksAndQueuesExportSpec extends SpecificationLike {

  import Summaries._

  val terminalQueues: Map[Terminal, Seq[Queues.Queue]] = Map(T1 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk))

  "Given a list of optional ints where there are no values " +
    "When I ask for the max value" +
    "I should see None" >> {
    val optionalInts: Seq[Option[Int]] = List(None, None, None)
    val maxInt: Option[Int] = optionalMax(optionalInts)

    maxInt === None
  }

  "Given a list of optional ints where there are some values and some Nones " +
    "When I ask for the max value" +
    "I should see Option(max value)" >> {
    val optionalInts: Seq[Option[Int]] = List(None, Option(10), Option(2), None)
    val maxInt: Option[Int] = optionalMax(optionalInts)

    maxInt === Option(10)
  }

  "Given a SortedMap of a single CrunchMinute with a TQM key and a SortedMap of a single StaffMinute with a TM key " >> {
    val terminal = T1
    val queues = Seq(Queues.EeaDesk, Queues.NonEeaDesk)

    val noon05 = SDate("2019-01-01T12:05:00")

    val pax = 5
    val deskRec = 7
    val waitTime = 8
    val depDesk = 9
    val depWait = 10
    val actDesk = 11
    val actWait = 12
    val cmNoon05 = CrunchMinute(terminal, Queues.EeaDesk, noon05.millisSinceEpoch, pax, 6, deskRec, waitTime, Option(depDesk), Option(depWait), Option(actDesk), Option(actWait))
    val shifts = 2
    val misc = 3
    val moves = 4
    val totalRec = deskRec + misc
    val smNoon05 = StaffMinute(terminal, noon05.millisSinceEpoch, shifts, misc, moves)

    val summaryStart = SDate("2019-01-01T12:00")
    val summaryPeriodMinutes = 15

    "When I ask for a queue summaries for a certain period " +
      "I should get the appropriate max or min values, or defaults where there was no data" >> {

      val allCms = mutable.SortedMap(cmNoon05.key -> cmNoon05)
      val result = queueSummariesForPeriod(allCms, queues, summaryStart, summaryPeriodMinutes)

      result === Seq(QueueSummary(pax, deskRec, waitTime, Option(actDesk), Option(actWait)), EmptyQueueSummary)
    }

    "When I ask for a staff summary for a certain period " +
      "I should get the appropriate max or min values, or defaults where there was no data" >> {

      val allSms = mutable.SortedMap(smNoon05.key -> smNoon05)
      val allCms = mutable.SortedMap(cmNoon05.key -> cmNoon05)
      val queueSummaries = queueSummariesForPeriod(allCms, queues, summaryStart, summaryPeriodMinutes)

      val smResult = staffSummaryForPeriod(allSms, queueSummaries, summaryStart, summaryPeriodMinutes)

      smResult === StaffSummary(shifts + moves, misc, moves, totalRec)
    }

    "When I ask for a TerminalSummary for a certain period " >> {
      val allSms = mutable.SortedMap(smNoon05.key -> smNoon05)
      val allCms = mutable.SortedMap(cmNoon05.key -> cmNoon05)

      val queueSummaries = queueSummariesForPeriod(allCms, queues, summaryStart, summaryPeriodMinutes)
      val smResult = staffSummaryForPeriod(allSms, queueSummaries, summaryStart, summaryPeriodMinutes)

      val tSummary = terminalSummaryForPeriod(allCms, allSms, queues, summaryStart, summaryPeriodMinutes)

      "I should get the appropriate Queue and Staff summaries" >> {
        tSummary === TerminalSummary(summaryStart, queueSummaries, smResult)
      }

      "I should get a correctly formatted csv live when requested" >> {
        tSummary.toCsv === s"2019-01-01,12:00,$pax,$waitTime,$deskRec,$actWait,$actDesk,0,0,0,,,$misc,$moves,${shifts + moves},$totalRec"
      }
    }
  }
}
