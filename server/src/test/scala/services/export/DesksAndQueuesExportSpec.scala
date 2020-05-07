package services.export

import akka.actor.{ActorRef, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.TestProbe
import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}
import drt.shared.Queues.{EeaDesk, Queue}
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{SDateLike, _}
import services.SDate
import services.crunch.CrunchTestLike
import services.exports.summaries.Summaries.{optionalMax, queueSummariesForPeriod, staffSummaryForPeriod, terminalSummaryForPeriod}
import services.exports.summaries.queues._
import services.exports.summaries.{GetSummaries, TerminalSummaryLike}
import services.graphstages.Crunch

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class DesksAndQueuesExportSpec extends CrunchTestLike {

  "CSV formatting" >> {
    val pax = 10d
    val desks = 5
    val waitTime = 11
    val actDesks: Option[Int] = Option(12)
    val actWaitTime: Option[Int] = Option(13)
    val queueSummary = QueueSummary(pax, desks, waitTime, actDesks, actWaitTime)
    val slotStart: SDateLike = SDate("2020-01-01T00:00")
    val miscStaff = 1
    val moves = 2
    val available = 3
    val recommended = 4
    val staffSummary = StaffSummary(available, miscStaff, moves, recommended)

    "Given a QueuesSummary" >> {
      "When I ask for its data row" >> {
        "I should see a correctly formatted csv string" >> {
          val expected = s"${Math.round(pax)},$waitTime,$desks,${actWaitTime.get},${actDesks.get}"
          queueSummary.toCsv === expected
        }
      }
    }
    "Given a StaffSummary" >> {
      "When I ask for its data row" >> {
        "I should see a correctly formatted csv string" >> {
          val expected = s"$miscStaff,$moves,$available,$recommended"
          staffSummary.toCsv === expected
        }
      }
    }
    "Given a QueuesSummarySlot with no queues and an empty staff summary" >> {
      "When I ask for its data row" >> {
        "I should see a correctly formatted csv string" >> {
          val terminalSummary = QueuesSummary(slotStart, Seq(), EmptyStaffSummary)
          val expected = s"2020-01-01,00:00,0,0,0,0"
          terminalSummary.toCsv === expected
        }
      }
    }
    "Given a QueuesSummarySlot with no queues and a staff summary" >> {
      "When I ask for its data row" >> {
        "I should see a correctly formatted csv string" >> {
          val terminalSummary = QueuesSummary(slotStart, Seq(), staffSummary)
          val expected = s"2020-01-01,00:00,$miscStaff,$moves,$available,$recommended"
          terminalSummary.toCsv === expected
        }
      }
    }
    "Given a QueuesSummarySlot with one queue and a staff summary" >> {
      "When I ask for its data row" >> {
        "I should see a correctly formatted csv string" >> {
          val terminalSummary = QueuesSummary(slotStart, Seq(queueSummary), staffSummary)
          val expected = s"2020-01-01,00:00,${Math.round(pax)},$waitTime,$desks,${actWaitTime.get},${actDesks.get},$miscStaff,$moves,$available,$recommended"
          terminalSummary.toCsv === expected
        }
      }
    }
  }

  val terminalQueues: Map[Terminal, Seq[Queues.Queue]] = Map(T1 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk))

  "Given a list of optional ints where there are no values " >> {
    "When I ask for the max value" >> {
      "I should see None" >> {
        val optionalInts: Seq[Option[Int]] = List(None, None, None)
        val maxInt: Option[Int] = optionalMax(optionalInts)

        maxInt === None
      }
    }
  }

  "Given a list of optional ints where there are some values and some Nones " >> {
    "When I ask for the max value" >> {
      "I should see Option(max value)" >> {
        val optionalInts: Seq[Option[Int]] = List(None, Option(10), Option(2), None)
        val maxInt: Option[Int] = optionalMax(optionalInts)

        maxInt === Option(10)
      }
    }
  }

  val terminal: Terminal = T1

  "Given a SortedMap of a single CrunchMinute with a TQM key and a SortedMap of a single StaffMinute with a TM key " >> {
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
        tSummary === QueuesSummary(summaryStart, queueSummaries, smResult)
      }

      "I should get a correctly formatted csv live when requested" >> {
        tSummary.toCsv === s"2019-01-01,12:00,$pax,$waitTime,$deskRec,$actWait,$actDesk,0,0,0,,,$misc,$moves,${shifts + moves},$totalRec"
      }
    }
  }

  val year = 2020
  val month = 1
  val day = 1
  val from: SDateLike = SDate(year, month, day, 0, 0, Crunch.europeLondonTimeZone)
  val queues = Seq(EeaDesk)

  import services.exports.Exports._

  "Given a desks summary actor for a given day which does not have any persisted data for that day and there is no port state available" >> {
    "When I ask for terminal summaries for that day" >> {
      "I should still get back 96 summaries" >> {
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], None, None))
        val portStateToSummaries = queueSummariesFromPortState(Seq(EeaDesk), 15, terminal, (_, _) => Future(PortState.empty))

        val result = Await.result(historicSummaryForDay(from, mockTerminalSummariesActor, GetSummaries, portStateToSummaries), 1 second)
          .asInstanceOf[TerminalQueuesSummary].summaries

        result.size === 96
      }
    }
  }

  "Given a desks summary actor for a given day which does not have any persisted data for that day and there is a port state available" >> {
    "When I ask for terminal summaries for that day" >> {
      "I should get back 96 summaries including one generated from the crunch & staff minutes in the port state" >> {
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], None, None))
        val noFlights = Iterable()
        val pax = 10
        val deskRec = 5
        val waitTime = 1
        val crunchMinutes = Iterable(CrunchMinute(terminal, EeaDesk, from.millisSinceEpoch, pax, 10, deskRec, waitTime, None, None, None, None, None))
        val shifts = 10
        val misc = 2
        val moves = 2
        val staffMinutes = Iterable(StaffMinute(terminal, from.millisSinceEpoch, shifts, misc, moves, None))
        val portState = PortState(noFlights, crunchMinutes, staffMinutes)

        val portStateToSummaries = queueSummariesFromPortState(Seq(EeaDesk), 15, terminal, (_, _) => Future(portState))

        val result = Await.result(historicSummaryForDay(from, mockTerminalSummariesActor, GetSummaries, portStateToSummaries), 1 second)
          .asInstanceOf[TerminalQueuesSummary].summaries

        val expected = QueuesSummary(from, List(QueueSummary(pax, deskRec, waitTime, None, None)), StaffSummary(shifts + moves, misc, moves, deskRec + misc))

        result.size === 96 && result.toList.contains(expected)
      }
    }
  }

  "Given a desks summary actor for a given day which does have some persisted data" >> {
    "When I ask for terminal summaries for that day" >> {
      "I should get back the persisted summaries" >> {
        val pax = 10
        val deskRec = 5
        val waitTime = 1
        val shifts = 10
        val misc = 2
        val moves = 2
        val persistedSummaries = TerminalQueuesSummary(queues, Iterable(QueuesSummary(from, List(QueueSummary(pax, deskRec, waitTime, None, None)), StaffSummary(shifts + moves, misc, moves, deskRec + misc))))
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], Option(persistedSummaries), None))

        val portStateToSummaries = queueSummariesFromPortState(Seq(EeaDesk), 15, terminal, (_, _) => Future(PortState.empty))
        val result = Await.result(historicSummaryForDay(from, mockTerminalSummariesActor, GetSummaries, portStateToSummaries), 1 second)

        result === persistedSummaries
      }
    }
  }

  "Given a desks summary actor for a given day which does not have any persisted data for that day and there is a port state available" >> {
    val noFlights = Iterable()
    val pax = 10
    val deskRec = 5
    val waitTime = 1
    val crunchMinutes = Iterable(CrunchMinute(terminal, EeaDesk, from.millisSinceEpoch, pax, 10, deskRec, waitTime, None, None, None, None, None))
    val shifts = 10
    val misc = 2
    val moves = 2
    val staffMinutes = Iterable(StaffMinute(terminal, from.millisSinceEpoch, shifts, misc, moves, None))
    val portState = PortState(noFlights, crunchMinutes, staffMinutes)

    "When I ask for terminal summaries for that day" >> {
      val portStateToSummaries = queueSummariesFromPortState(Seq(EeaDesk), 15, terminal, (_, _) => Future(portState))

      def eventualMaybeSummaries(actorProbe: ActorRef): Future[TerminalSummaryLike] = {
        historicSummaryForDay(from, actorProbe, GetSummaries, portStateToSummaries)
      }

      "I should get back 96 summaries including one generated from the crunch & staff minutes in the port state" >> {
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], None, None))
        val result = Await.result(eventualMaybeSummaries(mockTerminalSummariesActor), 1 second).asInstanceOf[TerminalQueuesSummary].summaries
        val expected = QueuesSummary(from, List(QueueSummary(pax, deskRec, waitTime, None, None)), StaffSummary(shifts + moves, misc, moves, deskRec + misc))

        result.size === 96 && result.toList.contains(expected)
      }

      "I should see the generated summaries sent to the summary actor for persistence" >> {
        val summariesProbe = TestProbe("summariesprobe")
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], None, Option(summariesProbe.ref)))
        Await.ready(eventualMaybeSummaries(mockTerminalSummariesActor), 1 second)

        summariesProbe.expectMsgClass(classOf[TerminalQueuesSummary])

        success
      }
    }
  }

  "Given a range of dates, and some mock summary actors containing data for those dates" >> {
    val pax = 10
    val deskRec = 5
    val waitTime = 1
    val shifts = 10
    val misc = 2
    val moves = 2

    def persistedSummaries(queues: Seq[Queue], from: SDateLike) = TerminalQueuesSummary(queues, Iterable(QueuesSummary(from, List(QueueSummary(pax, deskRec, waitTime, None, None)), StaffSummary(shifts + moves, misc, moves, deskRec + misc))))

    def mockTerminalSummariesActor: (SDateLike, Terminal) => ActorRef = (from: SDateLike, _: Terminal) => system.actorOf(Props(classOf[MockTerminalSummariesActor], Option(persistedSummaries(Seq(EeaDesk), from)), None))

    "When I ask for the summary data for the range of dates" >> {
      "Then I should see each date's mock actor's summary data" >> {
        val summaryActorProvider = mockTerminalSummariesActor

        val now: () => SDateLike = () => SDate("2020-06-01")
        val startDate = SDate("2020-01-01T00:00", Crunch.europeLondonTimeZone)
        val portStateToSummary = queueSummariesFromPortState(Seq(EeaDesk), 15, terminal, (_, _) => Future(PortState.empty))

        val exportStream = summaryForDaysCsvSource(startDate, 3, now, terminal, Option((summaryActorProvider, GetSummaries)), portStateToSummary)

        val value1 = exportStream.runWith(Sink.seq)(ActorMaterializer())
        val result = Await.result(value1, 1 second)

        val expected = List(
          persistedSummaries(queues, SDate("2020-01-01")).toCsvWithHeader,
          persistedSummaries(queues, SDate("2020-01-02")).toCsv,
          persistedSummaries(queues, SDate("2020-01-03")).toCsv
          )

        result === expected
      }
    }
  }
}
