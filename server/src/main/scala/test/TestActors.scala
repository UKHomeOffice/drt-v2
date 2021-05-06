package test

import actors.persistent.Sizes.oneMegaByte
import actors._
import actors.acking.AckingReceiver.Ack
import actors.daily._
import actors.routing.minutes.MinutesActorLike._
import actors.routing.minutes.{MinutesActorLike, QueueMinutesActor, StaffMinutesActor}
import actors.persistent.arrivals.{AclForecastArrivalsActor, PortForecastArrivalsActor, PortLiveArrivalsActor}
import actors.persistent.staffing.{FixedPointsActor, ShiftsActor, StaffMovementsActor}
import actors.persistent.{CrunchQueueActor, DeploymentQueueActor, ManifestRouterActor}
import actors.routing.FlightsRouterActor
import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.{ask, pipe}
import akka.persistence.{DeleteMessagesSuccess, DeleteSnapshotsSuccess, PersistentActor, SnapshotSelectionCriteria}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.dates.UtcDate
import org.slf4j.Logger
import services.SDate

import scala.collection.immutable.SortedSet
import scala.concurrent.Future


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

  class TestAclForecastArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends AclForecastArrivalsActor(oneMegaByte, now, expireAfterMillis) with Resettable {
    override def resetState(): Unit = state = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestPortForecastArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends PortForecastArrivalsActor(oneMegaByte, now, expireAfterMillis) {

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

  class TestPortLiveArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends PortLiveArrivalsActor(oneMegaByte, now, expireAfterMillis) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestVoyageManifestsActor(manifestLookup: ManifestLookup, manifestsUpdate: ManifestsUpdate, updatesSubscriber: ActorRef)
    extends ManifestRouterActor(manifestLookup, manifestsUpdate, updatesSubscriber) with Resettable {

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

  class MockAggregatedArrivalsActor extends Actor {
    override def receive: Receive = {
      case _ => sender() ! Ack
    }
  }

  class TestCrunchQueueActor(now: () => SDateLike, crunchOffsetMinutes: Int, durationMinutes: Int)
    extends CrunchQueueActor(now, crunchOffsetMinutes, durationMinutes) {
    def reset: Receive = {
      case ResetData =>
        readyToEmit = true
        maybeDaysQueueSource = None
        queuedDays = SortedSet()
        sender() ! Ack
    }

    override def receive: Receive = reset orElse super.receive
  }

  class TestDeploymentQueueActor(now: () => SDateLike, crunchOffsetMinutes: Int, durationMinutes: Int)
    extends DeploymentQueueActor(now, crunchOffsetMinutes, durationMinutes) {
    def reset: Receive = {
      case ResetData =>
        readyToEmit = true
        maybeDaysQueueSource = None
        queuedDays = SortedSet()
        sender() ! Ack
    }

    override def receive: Receive = reset orElse super.receive
  }

  trait TestMinuteActorLike[A, B <: WithTimeAccessor] extends MinutesActorLike[A, B] {
    val resetData: (Terminal, MillisSinceEpoch) => Future[Any]
    var terminalDaysUpdated: Set[(Terminal, MillisSinceEpoch)] = Set()

    private def addToTerminalDays(container: MinutesContainer[A, B]): Unit = {
      partitionUpdates(container).keys.foreach {
        case (terminal, date) => terminalDaysUpdated = terminalDaysUpdated + ((terminal, SDate(date).millisSinceEpoch))
      }
    }

    def resetReceive: Receive = {
      case container: MinutesContainer[A, B] =>
        val replyTo = sender()
        addToTerminalDays(container)
        handleUpdatesAndAck(container, replyTo)

      case ResetData =>
        Future
          .sequence(terminalDaysUpdated.map { case (t, d) => resetData(t, d) })
          .map { _ =>
            terminalDaysUpdated = Set()
            Ack
          }
          .pipeTo(sender())
    }

  }

  class TestStaffMinutesActor(terminals: Iterable[Terminal],
                              lookup: MinutesLookup[StaffMinute, TM],
                              updateMinutes: MinutesUpdate[StaffMinute, TM],
                              val resetData: (Terminal, MillisSinceEpoch) => Future[Any])
    extends StaffMinutesActor(terminals, lookup, updateMinutes) with TestMinuteActorLike[StaffMinute, TM] {
    override def receive: Receive = resetReceive orElse super.receive
  }

  class TestQueueMinutesActor(terminals: Iterable[Terminal],
                              lookup: MinutesLookup[CrunchMinute, TQM],
                              updateMinutes: MinutesUpdate[CrunchMinute, TQM],
                              val resetData: (Terminal, MillisSinceEpoch) => Future[Any],
                              updatesSubscriber: ActorRef)
    extends QueueMinutesActor(terminals, lookup, updateMinutes, updatesSubscriber) with TestMinuteActorLike[CrunchMinute, TQM] {
    override def receive: Receive = resetReceive orElse super.receive
  }

  class DummyActor extends Actor {
    override def receive: Receive = {
      case _ =>
    }
  }


  class TestFlightsRouterActor(subscriber: ActorRef,
                               terminals: Iterable[Terminal],
                               byDayLookup: FlightsLookup,
                               updateMinutes: FlightsUpdate,
                               val resetData: (Terminal, UtcDate) => Future[Any])
    extends FlightsRouterActor(subscriber, terminals, byDayLookup, updateMinutes) {
    override def receive: Receive = resetReceive orElse super.receive

    var terminalDaysUpdated: Set[(Terminal, UtcDate)] = Set()

    private def addToTerminalDays(container: ArrivalsDiff): Unit = {
      partitionUpdates(container).keys.foreach {
        case (terminal, date) => terminalDaysUpdated = terminalDaysUpdated + ((terminal, date))
      }
    }

    def resetReceive: Receive = {
      case container: ArrivalsDiff =>
        val replyTo = sender()
        addToTerminalDays(container)
        handleUpdatesAndAck(container, replyTo)

      case ResetData =>
        Future
          .sequence(terminalDaysUpdated.map { case (t, d) =>
            resetData(t, d)
          })
          .map { _ =>
            terminalDaysUpdated = Set()
            Ack
          }
          .pipeTo(sender())
    }
  }

  class TestPartitionedPortStateActor(flightsActor: ActorRef,
                                      queuesActor: ActorRef,
                                      staffActor: ActorRef,
                                      queueUpdatesActor: ActorRef,
                                      staffUpdatesActor: ActorRef,
                                      flightUpdatesActor: ActorRef,
                                      now: () => SDateLike,
                                      queues: Map[Terminal, Seq[Queue]],
                                      journalType: StreamingJournalLike)
    extends PartitionedPortStateActor(
      flightsActor,
      queuesActor,
      staffActor,
      queueUpdatesActor,
      staffUpdatesActor,
      flightUpdatesActor,
      now,
      queues,
      journalType) {

    val actorClearRequests = Map(
      flightsActor -> ResetData,
      queuesActor -> ResetData,
      staffActor -> ResetData,
      queueUpdatesActor -> PurgeAll,
      staffUpdatesActor -> PurgeAll,
      flightUpdatesActor -> PurgeAll
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

  class TestTerminalDayFlightActor(year: Int,
                                   month: Int,
                                   day: Int,
                                   terminal: Terminal,
                                   now: () => SDateLike) extends TerminalDayFlightActor(year, month, day, terminal, now, None, None) with Resettable {
    override def resetState(): Unit = state = FlightsWithSplits.empty

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class QueueTestUpdatesSupervisor(now: () => SDateLike,
                                   terminals: List[Terminal],
                                   updatesActorFactory: (Terminal, SDateLike) => Props)
    extends TestUpdatesSupervisor[CrunchMinute, TQM](now, terminals, updatesActorFactory)

  class StaffTestUpdatesSupervisor(now: () => SDateLike,
                                   terminals: List[Terminal],
                                   updatesActorFactory: (Terminal, SDateLike) => Props)
    extends TestUpdatesSupervisor[StaffMinute, TM](now, terminals, updatesActorFactory)

  abstract class TestUpdatesSupervisor[A, B <: WithTimeAccessor](now: () => SDateLike,
                                                                 terminals: List[Terminal],
                                                                 updatesActorFactory: (Terminal, SDateLike) => Props)
    extends UpdatesSupervisor(now, terminals, updatesActorFactory) {
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

  class TestFlightUpdatesSupervisor(now: () => SDateLike,
                                    terminals: List[Terminal],
                                    updatesActorFactory: (Terminal, SDateLike) => Props)
    extends FlightUpdatesSupervisor(now, terminals, updatesActorFactory) {

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
