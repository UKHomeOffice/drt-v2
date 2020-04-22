package test

import actors.DrtStaticParameters.expireAfterMillis
import actors.Sizes.oneMegaByte
import actors._
import actors.acking.AckingReceiver.Ack
import actors.daily.{TerminalDayQueuesActor, TerminalDayStaffActor}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{ask, pipe}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{AirportConfig, MilliTimes, PortCode, SDateLike, TM, TQM}
import services.SDate
import slickdb.ArrivalTable

import scala.concurrent.{ExecutionContext, Future}


object TestActors {

  case object ResetData

  class TestForecastBaseArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends ForecastBaseArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def reset: Receive = {
      case ResetData =>
        state.clear()
        sender() ! Ack
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  class TestForecastPortArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends ForecastPortArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def reset: Receive = {
      case ResetData =>
        state.clear()
        sender() ! Ack
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  class TestLiveArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends LiveArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def reset: Receive = {
      case ResetData =>
        state.clear()
        sender() ! Ack
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  class TestVoyageManifestsActor(override val now: () => SDateLike, expireAfterMillis: Int, snapshotInterval: Int)
    extends VoyageManifestsActor(oneMegaByte, now, expireAfterMillis, Option(snapshotInterval)) {

    def reset: Receive = {
      case ResetData =>
        state = initialState
        sender() ! Ack
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  class TestShiftsActor(override val now: () => SDateLike,
                        override val expireBefore: () => SDateLike) extends ShiftsActor(now, expireBefore) {

    def reset: Receive = {
      case ResetData =>
        state = initialState
        subscribers = List()
        sender() ! Ack
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  class TestFixedPointsActor(override val now: () => SDateLike) extends FixedPointsActor(now) {

    def reset: Receive = {
      case ResetData =>
        state = initialState
        subscribers = List()
        sender() ! Ack
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  class TestStaffMovementsActor(override val now: () => SDateLike,
                                override val expireBefore: () => SDateLike) extends StaffMovementsActor(now, expireBefore) {

    def reset: Receive = {
      case ResetData =>
        state = initialState
        subscribers = List()
        sender() ! Ack
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  class TestAggregatedArrivalsActor() extends {
    private val portCode = PortCode("LHR")
  } with AggregatedArrivalsActor(ArrivalTable(portCode, PostgresTables)) {
    def reset: Receive = {
      case ResetData =>
        sender() ! Ack
    }

    override def receive: Receive = reset orElse super.receive
  }

  class TestPortStateActor(live: ActorRef, forecast: ActorRef, now: () => SDateLike, liveDaysAhead: Int)
    extends PortStateActor(live, forecast, now, liveDaysAhead) {
    def reset: Receive = {
      case ResetData =>
        state.clear()
        sender() ! Ack
    }

    override def receive: Receive = reset orElse super.receive
  }

  trait TestMinuteActorLike[A, B] extends MinutesActor[A, B] {
    val resetData: (Terminal, MillisSinceEpoch) => Future[Any]
    var terminalDaysUpdated: Set[(Terminal, MillisSinceEpoch)] = Set()

    private def addToTerminalDays(container: MinutesContainer[A, B]): Unit = {
      groupByTerminalAndDay(container).keys.foreach {
        case (terminal, date) => terminalDaysUpdated = terminalDaysUpdated + ((terminal, date.millisSinceEpoch))
      }
    }

    def resetReceive: Receive = {
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

  class TestStaffMinutesActor(now: () => SDateLike,
                              terminals: Iterable[Terminal],
                              lookupPrimary: MinutesLookup[StaffMinute, TM],
                              lookupSecondary: MinutesLookup[StaffMinute, TM],
                              updateMinutes: MinutesUpdate[StaffMinute, TM],
                              val resetData: (Terminal, MillisSinceEpoch) => Future[Any])
    extends StaffMinutesActor(now, terminals, lookupPrimary, lookupSecondary, updateMinutes) with TestMinuteActorLike[StaffMinute, TM] {
    override def receive: Receive = resetReceive orElse super.receive
  }

  class TestQueueMinutesActor(now: () => SDateLike,
                              terminals: Iterable[Terminal],
                              lookupPrimary: MinutesLookup[CrunchMinute, TQM],
                              lookupSecondary: MinutesLookup[CrunchMinute, TQM],
                              updateMinutes: MinutesUpdate[CrunchMinute, TQM],
                              val resetData: (Terminal, MillisSinceEpoch) => Future[Any])
    extends QueueMinutesActor(now, terminals, lookupPrimary, lookupSecondary, updateMinutes) with TestMinuteActorLike[CrunchMinute, TQM] {
    override def receive: Receive = resetReceive orElse super.receive
  }

  object TestPartitionedPortStateActor {
    def apply(now: () => SDateLike, airportConfig: AirportConfig)
             (implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
      val lookups: MinuteLookups = MinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)
      val flightsActor: ActorRef = system.actorOf(Props(new TestFlightsStateActor(None, Sizes.oneMegaByte, "crunch-live-state-actor", airportConfig.queuesByTerminal, now, expireAfterMillis)))
      val queuesActor: ActorRef = lookups.queueMinutesActor(classOf[TestQueueMinutesActor])
      val staffActor: ActorRef = lookups.staffMinutesActor(classOf[TestStaffMinutesActor])
      system.actorOf(Props(new PartitionedPortStateActor(flightsActor, queuesActor, staffActor, now)))
    }
  }

  class TestPartitionedPortStateActor(flightsActor: ActorRef,
                                      queuesActor: ActorRef,
                                      staffActor: ActorRef,
                                      now: () => SDateLike) extends PartitionedPortStateActor(flightsActor, queuesActor, staffActor, now) {
    def myReceive: Receive = {
      case ResetData =>
        Future
          .sequence(Seq(flightsActor, queuesActor, staffActor).map(_.ask(ResetData)))
          .map(_ => Ack)
          .pipeTo(sender())
    }

    override def receive: Receive = myReceive orElse super.receive
  }

  class TestTerminalDayQueuesActor(year: Int,
                                   month: Int,
                                   day: Int,
                                   terminal: Terminal,
                                   now: () => SDateLike) extends TerminalDayQueuesActor(year, month, day, terminal, now) {
    def myReceive: Receive = {
      case ResetData =>
        log.warn("Received ResetData request. Deleting all messages & snapshots")
        deleteMessages(Long.MaxValue)
        deleteSnapshot(Long.MaxValue)
        sender() ! Ack
    }

    override def receive: Receive = myReceive orElse super.receive
  }

  class TestTerminalDayStaffActor(year: Int,
                                  month: Int,
                                  day: Int,
                                  terminal: Terminal,
                                  now: () => SDateLike) extends TerminalDayStaffActor(year, month, day, terminal, now) {
    def myReceive: Receive = {
      case ResetData =>
        log.warn("Received ResetData request. Deleting all messages & snapshots")
        deleteMessages(Long.MaxValue)
        deleteSnapshot(Long.MaxValue)
        sender() ! Ack
    }

    override def receive: Receive = myReceive orElse super.receive
  }

  class TestFlightsStateActor(initialMaybeSnapshotInterval: Option[Int],
                              initialSnapshotBytesThreshold: Int,
                              name: String,
                              portQueues: Map[Terminal, Seq[Queue]],
                              now: () => SDateLike,
                              expireAfterMillis: Int) extends FlightsStateActor(initialMaybeSnapshotInterval, initialSnapshotBytesThreshold, name, portQueues, now, expireAfterMillis) {
    def myReceive: Receive = {
      case ResetData =>
        log.warn("Received ResetData request. Deleting all messages & snapshots")
        deleteMessages(Long.MaxValue)
        deleteSnapshot(Long.MaxValue)
        state.clear()
        sender() ! Ack
    }

    override def receive: Receive = myReceive orElse super.receive
  }

  class TestCrunchStateActor(snapshotInterval: Int,
                             name: String,
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
      forecastMaxMillis = () => now().addDays(2).millisSinceEpoch) {

    def reset: Receive = {
      case ResetData =>
        state = initialState
        sender() ! Ack
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  def logMessage(m: Any): String = s"Got this message: ${m.getClass} but not doing anything because this is a test."
}
