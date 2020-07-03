package test

import actors.DrtStaticParameters.expireAfterMillis
import actors.Sizes.oneMegaByte
import actors._
import actors.acking.AckingReceiver.Ack
import actors.daily._
import actors.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import actors.minutes.{MinutesActorLike, QueueMinutesActor, StaffMinutesActor}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.persistence.{DeleteMessagesSuccess, DeleteSnapshotsSuccess, PersistentActor, SnapshotSelectionCriteria}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.Logger
import services.SDate
import slickdb.ArrivalTable

import scala.concurrent.{ExecutionContext, Future}


object TestActors {

  case object ResetData

  trait Resettable extends PersistentActor {
    val log: Logger
    var replyTo: Option[ActorRef] = None
    var deletedMessages: Boolean = false
    var deletedSnapshots: Boolean = false

    def resetState(): Unit

    def deletionFinished: Boolean = deletedMessages && deletedSnapshots

    def resetBehaviour: Receive = {
      case ResetData =>
        replyTo = Option(sender())
        log.warn("Received ResetData request. Deleting all messages & snapshots")
        deleteMessages(Long.MaxValue)
        deleteSnapshots(SnapshotSelectionCriteria(minSequenceNr = 0L, maxSequenceNr = Long.MaxValue))
      case _: DeleteMessagesSuccess =>
        deletedMessages = true
        ackIfDeletionFinished()
      case _: DeleteSnapshotsSuccess =>
        deletedSnapshots = true
        ackIfDeletionFinished()
    }

    def ackIfDeletionFinished(): Unit = replyTo.foreach { r =>
      if (deletionFinished) {
        log.info("Finished deletions")
        resetState()
        deletedMessages = false
        deletedSnapshots = false
        replyTo = None
        r ! Ack
      }
    }
  }

  class TestForecastBaseArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends ForecastBaseArrivalsActor(oneMegaByte, now, expireAfterMillis) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestForecastPortArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends ForecastPortArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def resetBehaviour: Receive = {
      case ResetData =>
        state.clear()
        sender() ! Ack
    }

    override def receiveRecover: Receive = {
      case _ => Unit
    }

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestLiveArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends LiveArrivalsActor(oneMegaByte, now, expireAfterMillis) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestVoyageManifestsActor(override val now: () => SDateLike, expireAfterMillis: Int, snapshotInterval: Int)
    extends VoyageManifestsActor(oneMegaByte, now, expireAfterMillis, Option(snapshotInterval)) with Resettable {
    override def resetState(): Unit = state = initialState

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestShiftsActor(override val now: () => SDateLike,
                        override val expireBefore: () => SDateLike) extends ShiftsActor(now, expireBefore) with Resettable {
    override def resetState(): Unit = {
      state = initialState
      subscribers = List()
    }

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestFixedPointsActor(override val now: () => SDateLike) extends FixedPointsActor(now) with Resettable {
    override def resetState(): Unit = {
      state = initialState
      subscribers = List()
    }

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestStaffMovementsActor(override val now: () => SDateLike,
                                override val expireBefore: () => SDateLike) extends StaffMovementsActor(now, expireBefore) with Resettable {
    override def resetState(): Unit = {
      state = initialState
      subscribers = List()
    }

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestAggregatedArrivalsActor() extends {
    private val portCode = PortCode("TEST")
  } with AggregatedArrivalsActor(ArrivalTable(portCode, PostgresTables)) {
    def reset: Receive = {
      case ResetData =>
        sender() ! Ack
    }

    override def receive: Receive = reset orElse super.receive
  }

  class TestPortStateActor(liveProps: Props, forecastProps: Props, now: () => SDateLike, liveDaysAhead: Int, queues: Map[Terminal, Seq[Queue]])
    extends PortStateActor(liveProps, forecastProps, now, liveDaysAhead, queues) {
    def reset: Receive = {
      case ResetData =>
        val replyTo = sender()
        state.clear()
        Future
          .sequence(List(liveCrunchStateActor.ask(ResetData), forecastCrunchStateActor.ask(ResetData)))
          .foreach(_ => replyTo ! Ack)
    }

    override def regularBehaviour: Receive = reset orElse super.regularBehaviour
  }

  trait TestMinuteActorLike[A, B <: WithTimeAccessor] extends MinutesActorLike[A, B] {
    val resetData: (Terminal, MillisSinceEpoch) => Future[Any]
    var terminalDaysUpdated: Set[(Terminal, MillisSinceEpoch)] = Set()

    private def addToTerminalDays(container: MinutesContainer[A, B]): Unit = {
      groupByTerminalAndDay(container).keys.foreach {
        case (terminal, date) => terminalDaysUpdated = terminalDaysUpdated + ((terminal, date.millisSinceEpoch))
      }
    }

    def resetReceive: Receive = {
      case container: MinutesContainer[A, B] =>
        val replyTo = sender()
        addToTerminalDays(container)
        handleUpdatesAndAck(container, replyTo)

      case ResetData =>
        Future
          .sequence(terminalDaysUpdated.map { case (t, d) =>
            println(s"\n\n**Sending ResetData to $t / ${SDate(d).toISOString()}")
            resetData(t, d)
          })
          .map { _ =>
            terminalDaysUpdated = Set()
            Ack
          }
          .pipeTo(sender())
    }

  }

  class TestStaffMinutesActor(now: () => SDateLike,
                              terminals: Iterable[Terminal],
                              lookup: MinutesLookup[StaffMinute, TM],
                              lookupLegacy: MinutesLookup[StaffMinute, TM],
                              updateMinutes: MinutesUpdate[StaffMinute, TM],
                              val resetData: (Terminal, MillisSinceEpoch) => Future[Any])
    extends StaffMinutesActor(now, terminals, lookup, lookupLegacy, updateMinutes) with TestMinuteActorLike[StaffMinute, TM] {
    override def receive: Receive = resetReceive orElse super.receive
  }

  class TestQueueMinutesActor(now: () => SDateLike,
                              terminals: Iterable[Terminal],
                              lookup: MinutesLookup[CrunchMinute, TQM],
                              lookupLegacy: MinutesLookup[CrunchMinute, TQM],
                              updateMinutes: MinutesUpdate[CrunchMinute, TQM],
                              val resetData: (Terminal, MillisSinceEpoch) => Future[Any])
    extends QueueMinutesActor(now, terminals, lookup, lookupLegacy, updateMinutes) with TestMinuteActorLike[CrunchMinute, TQM] {
    override def receive: Receive = resetReceive orElse super.receive
  }

  object TestPartitionedPortStateActor {
    def apply(now: () => SDateLike, airportConfig: AirportConfig, streamingJournal: StreamingJournalLike)
             (implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
      val lookups = TestMinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)
      val flightsActor: ActorRef = system.actorOf(Props(new TestFlightsStateActor(None, Sizes.oneMegaByte, "crunch-live-state-actor", now, expireAfterMillis, airportConfig.queuesByTerminal)))
      val queuesActor: ActorRef = lookups.queueMinutesActor
      val staffActor: ActorRef = lookups.staffMinutesActor
      system.actorOf(Props(new TestPartitionedPortStateActor(flightsActor, queuesActor, staffActor, now, airportConfig.terminals.toList, streamingJournal)))
    }
  }

  trait TestPartitionedPortStateActorLike extends PartitionedPortStateActorLike {
    override val queueUpdatesSupervisor: ActorRef = context.system.actorOf(Props(new TestUpdatesSupervisor[CrunchMinute, TQM](now, terminals, queueUpdatesProps)))
    override val staffUpdatesSupervisor: ActorRef = context.system.actorOf(Props(new TestUpdatesSupervisor[StaffMinute, TM](now, terminals, staffUpdatesProps)))
  }

  class TestPartitionedPortStateActor(flightsActor: ActorRef,
                                      queuesActor: ActorRef,
                                      staffActor: ActorRef,
                                      now: () => SDateLike,
                                      terminals: List[Terminal],
                                      journalType: StreamingJournalLike) extends PartitionedPortStateActor(flightsActor, queuesActor, staffActor, now, terminals, journalType) with TestPartitionedPortStateActorLike {
    val actorClearRequests = Map(
      flightsActor -> ResetData,
      queuesActor -> ResetData,
      staffActor -> ResetData,
      queueUpdatesSupervisor -> PurgeAll,
      staffUpdatesSupervisor -> PurgeAll
      )

    def myReceive: Receive = {
      case ResetData =>
        Future
          .sequence(actorClearRequests.map {
            case (actor, request) => actor.ask(request)
          })
          .map(_ => Ack)
          .pipeTo(sender())
    }

    override def receive: Receive = myReceive orElse super.receive
  }

  class TestTerminalDayQueuesActor(year: Int,
                                   month: Int,
                                   day: Int,
                                   terminal: Terminal,
                                   now: () => SDateLike) extends TerminalDayQueuesActor(year, month, day, terminal, now, None) with Resettable {
    override def resetState(): Unit = state = Map()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestTerminalDayStaffActor(year: Int,
                                  month: Int,
                                  day: Int,
                                  terminal: Terminal,
                                  now: () => SDateLike) extends TerminalDayStaffActor(year, month, day, terminal, now, None) with Resettable {
    override def resetState(): Unit = state = Map()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestFlightsStateActor(initialMaybeSnapshotInterval: Option[Int],
                              initialSnapshotBytesThreshold: Int,
                              name: String,
                              now: () => SDateLike,
                              expireAfterMillis: Int,
                              queues: Map[Terminal, Seq[Queue]]) extends FlightsStateActor(now, expireAfterMillis, queues, SDate("1970-01-01")) with Resettable {
    override def resetState(): Unit = state = FlightsWithSplits.empty

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestCrunchStateActor(name: String,
                             portQueues: Map[Terminal, Seq[Queue]],
                             override val now: () => SDateLike,
                             expireAfterMillis: Int,
                             purgePreviousSnapshots: Boolean)
    extends CrunchStateActor(
      initialMaybeSnapshotInterval = None,
      initialSnapshotBytesThreshold = oneMegaByte,
      name = name,
      portQueues = portQueues,
      now = now,
      expireAfterMillis = expireAfterMillis,
      purgePreviousSnapshots = purgePreviousSnapshots,
      forecastMaxMillis = () => now().addDays(2).millisSinceEpoch) with Resettable {
    override def resetState(): Unit = state = PortStateMutable.empty

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestUpdatesSupervisor[A, B <: WithTimeAccessor](now: () => SDateLike,
                                                        terminals: List[Terminal],
                                                        updatesActorFactory: (Terminal, SDateLike) => Props) extends UpdatesSupervisor(now, terminals, updatesActorFactory) {
    def testReceive: Receive = {
      case PurgeAll =>
        val replyTo = sender()
        log.info(s"Received PurgeAll")
        Future.sequence(streamingUpdateActors.values.map(actor => killActor.ask(Terminate(actor)))).foreach { _ =>
          streamingUpdateActors = Map()
          lastRequests = Map()
          replyTo ! Ack
        }
    }

    override def receive: Receive = testReceive orElse super.receive
  }

}
