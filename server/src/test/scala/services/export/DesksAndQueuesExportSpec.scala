package services.export

import actors.{GetPortStateForTerminal, RecoveryActorLike, Sizes}
import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, PortStateError, StaffMinute}
import drt.shared.Queues.{EeaDesk, Queue}
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{SDateLike, _}
import org.joda.time.DateTimeZone
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.SpecificationLike
import scalapb.GeneratedMessage
import services.SDate
import services.graphstages.Crunch

import scala.collection.{immutable, mutable}
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration._

class DesksAndQueuesExportSpec extends SpecificationLike {

  import Summaries._

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
          val terminalSummary = TerminalSummary(slotStart, Seq(), EmptyStaffSummary)
          val expected = s"2020-01-01,00:00,0,0,0,0"
          terminalSummary.toCsv === expected
        }
      }
    }
    "Given a QueuesSummarySlot with no queues and a staff summary" >> {
      "When I ask for its data row" >> {
        "I should see a correctly formatted csv string" >> {
          val terminalSummary = TerminalSummary(slotStart, Seq(), staffSummary)
          val expected = s"2020-01-01,00:00,$miscStaff,$moves,$available,$recommended"
          terminalSummary.toCsv === expected
        }
      }
    }
    "Given a QueuesSummarySlot with one queue and a staff summary" >> {
      "When I ask for its data row" >> {
        "I should see a correctly formatted csv string" >> {
          val terminalSummary = TerminalSummary(slotStart, Seq(queueSummary), staffSummary)
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
        tSummary === TerminalSummary(summaryStart, queueSummaries, smResult)
      }

      "I should get a correctly formatted csv live when requested" >> {
        tSummary.toCsv === s"2019-01-01,12:00,$pax,$waitTime,$deskRec,$actWait,$actDesk,0,0,0,,,$misc,$moves,${shifts + moves},$totalRec"
      }
    }
  }

  implicit val system: ActorSystem = ActorSystem("queues-summary")
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(5 seconds)

  val year = 2020
  val month = 1
  val day = 1
  val from: SDateLike = SDate(year, month, day, 0, 0, Crunch.europeLondonTimeZone)

  "Given a desks summary actor for a given day which does not have any persisted data for that day and there is no port state available" >> {
    "When I ask for terminal summaries for that day" >> {
      "I should get back a None, in reflection of the missing data" >> {
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], None, None))
        val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], None))
        val portStateToSummaries = terminalSummariesFromPortState(Seq(EeaDesk), 15)

        val result = Await.result(historicSummaryForDay(terminal, from, mockTerminalSummariesActor, GetSummaries, _ => mockPortStateActor, portStateToSummaries), 1 second)

        result === None
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
        val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], Option(portState)))

        val portStateToSummaries = terminalSummariesFromPortState(Seq(EeaDesk), 15)

        val result = Await.result(historicSummaryForDay(terminal, from, mockTerminalSummariesActor, GetSummaries, _ => mockPortStateActor, portStateToSummaries), 1 second).get.summaries

        val expected = TerminalSummary(from, List(QueueSummary(pax, deskRec, waitTime, None, None)), StaffSummary(shifts + moves, misc, moves, deskRec + misc))

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
        val persistedSummaries = TerminalSummaries(Iterable(TerminalSummary(from, List(QueueSummary(pax, deskRec, waitTime, None, None)), StaffSummary(shifts + moves, misc, moves, deskRec + misc))))
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], Option(persistedSummaries), None))
        val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], None))

        val portStateToSummaries = terminalSummariesFromPortState(Seq(EeaDesk), 15)
        val result = Await.result(historicSummaryForDay(terminal, from, mockTerminalSummariesActor, GetSummaries, _ => mockPortStateActor, portStateToSummaries), 1 second).get

        result === persistedSummaries
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

    val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], None))

    def persistedSummaries(from: SDateLike) = TerminalSummaries(Iterable(TerminalSummary(from, List(QueueSummary(pax, deskRec, waitTime, None, None)), StaffSummary(shifts + moves, misc, moves, deskRec + misc))))

    def mockTerminalSummariesActor(from: SDateLike) = system.actorOf(Props(classOf[MockTerminalSummariesActor], Option(persistedSummaries(from)), None))

    "When I ask for the summary data for the range of dates" >> {
      "Then I should see each date's mock actor's summary data" >> {
        val year = 2020
        val month = 1
        val day = 1
        val startDate = SDate(year, month, day, 0, 0, Crunch.europeLondonTimeZone)
        val exportStream = Source(0 until 3)
          .mapAsync(1) { dayOffset =>
            val from = startDate.addDays(dayOffset)
            val portStateToSummaries = terminalSummariesFromPortState(Seq(EeaDesk), 15)
            historicSummaryForDay(terminal, from, mockTerminalSummariesActor(from), GetSummaries, _ => mockPortStateActor, portStateToSummaries)
          }
          .collect { case Some(summaries) => summaries.summaries.map(_.toCsv) }
          .flatMapConcat { summaries => Source(summaries.toList) }

        val value1 = exportStream.runWith(Sink.seq)(ActorMaterializer())
        val result = Await.result(value1, 1 second)

        val expected = List(
          persistedSummaries(SDate("2020-01-01")).summaries.map(_.toCsv),
          persistedSummaries(SDate("2020-01-02")).summaries.map(_.toCsv),
          persistedSummaries(SDate("2020-01-03")).summaries.map(_.toCsv)
        ).flatten

        result === expected
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
    val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], Option(portState)))

    "When I ask for terminal summaries for that day" >> {
      val portStateToSummaries = terminalSummariesFromPortState(Seq(EeaDesk), 15)

      def eventualMaybeSummaries(actorProbe: ActorRef): Future[Option[TerminalSummaries]] = {
        historicSummaryForDay(terminal, from, actorProbe, GetSummaries, _ => mockPortStateActor, portStateToSummaries)
      }

      "I should get back 96 summaries including one generated from the crunch & staff minutes in the port state" >> {
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], None, None))
        val result = Await.result(eventualMaybeSummaries(mockTerminalSummariesActor), 1 second).get.summaries
        val expected = TerminalSummary(from, List(QueueSummary(pax, deskRec, waitTime, None, None)), StaffSummary(shifts + moves, misc, moves, deskRec + misc))

        result.size === 96 && result.toList.contains(expected)
      }

      "I should see the generated summaries sent to the summary actor for persistence" >> {
        val summariesProbe = TestProbe("summariesprobe")
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], None, Option(summariesProbe.ref)))
        Await.ready(eventualMaybeSummaries(mockTerminalSummariesActor), 1 second)

        summariesProbe.expectMsgClass(classOf[TerminalSummaries])

        success
      }
    }
  }

  def exportDesksAndQueuesBetweenTimeStampsCSV(start: String,
                                               end: String,
                                               terminalName: String): String = {
    val terminal = Terminal(terminalName)
    "nothing"
  }

  def summaryForDay[S](now: () => SDateLike,
                       terminal: Terminal,
                       from: SDateLike,
                       summaryActor: AskableActorRef,
                       request: Any,
                       portStateActorForDate: SDateLike => AskableActorRef,
                       summaryFromPortState: (SDateLike, SDateLike, PortState) => Option[S])
                      (implicit system: ActorSystem,
                       ec: ExecutionContext,
                       timeout: Timeout): Future[Option[S]] = {
    val isHistoric = from.millisSinceEpoch < Crunch.getLocalLastMidnight(now().addDays(-2)).millisSinceEpoch

    if (isHistoric) historicSummaryForDay(terminal, from, summaryActor, request, portStateActorForDate, summaryFromPortState)
    else extractDayForTerminal(terminal, from, portStateActorForDate(from), summaryFromPortState)
  }

  def historicSummaryForDay[S](terminal: Terminal,
                               from: SDateLike,
                               summaryActor: AskableActorRef,
                               request: Any,
                               portStateActorProvider: SDateLike => AskableActorRef,
                               fromPortState: (SDateLike, SDateLike, PortState) => Option[S])
                              (implicit system: ActorSystem,
                               ec: ExecutionContext,
                               timeout: Timeout): Future[Option[S]] = summaryActor
    .ask(request)
    .asInstanceOf[Future[Option[S]]]
    .flatMap {
      case None =>
        extractDayForTerminal(terminal, from, portStateActorProvider(from), fromPortState).flatMap {
          case None => Future(None)
          case Some(extract) => summaryActor
            .ask(extract)
            .map(_ => Option(extract))
        }
      case someSummaries => Future(someSummaries)
    }

  def extractDayForTerminal[S](terminal: Terminal,
                               startTime: SDateLike,
                               portStateActor: AskableActorRef,
                               fromPortState: (SDateLike, SDateLike, PortState) => Option[S])
                              (implicit ec: ExecutionContext): Future[Option[S]] = {
    val endTime = startTime.addDays(1)
    val terminalRequest = GetPortStateForTerminal(startTime.millisSinceEpoch, endTime.millisSinceEpoch, terminal)
    portStateActor
      .ask(terminalRequest)(new Timeout(5 seconds))
      .asInstanceOf[Future[Option[PortState]]]
      .map {
        case None => None
        case Some(portState) => fromPortState(startTime, endTime, portState)
      }
  }

  def terminalSummariesFromPortState: (Seq[Queue], Int) => (SDateLike, SDateLike, PortState) => Option[TerminalSummaries] =
    (queues: Seq[Queue], summaryLengthMinutes: Int) => (from: SDateLike, to: SDateLike, portState: PortState) => {
      Option(TerminalSummaries((from.millisSinceEpoch until to.millisSinceEpoch by summaryLengthMinutes * Crunch.oneMinuteMillis).map { millis =>
        terminalSummaryForPeriod(portState.crunchMinutes, portState.staffMinutes, queues, SDate(millis), summaryLengthMinutes)
      }))
    }
}

case object GetSummaries

class MockTerminalSummariesActor(optionalSummaries: Option[TerminalSummaries],
                                 maybeTestProbe: Option[ActorRef]) extends Actor {
  override def receive: Receive = {
    case GetSummaries =>
      sender() ! optionalSummaries

    case summaries: TerminalSummaries =>
      maybeTestProbe.foreach(_ ! summaries)
      sender() ! "ok"
  }
}

class MockPortStateActor(optionalPortState: Option[PortState]) extends Actor {
  override def receive: Receive = {
    case GetPortStateForTerminal(_, _, _) => sender() ! optionalPortState
  }
}
