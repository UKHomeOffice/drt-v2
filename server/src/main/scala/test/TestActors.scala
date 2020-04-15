package test

import actors.Sizes.oneMegaByte
import actors._
import actors.acking.AckingReceiver.Ack
import actors.daily.{TerminalDayQueuesActor, TerminalDayStaffActor}
import akka.actor.Props
import akka.pattern.{AskableActorRef, pipe}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{PortCode, SDateLike}
import services.SDate
import slickdb.ArrivalTable

import scala.concurrent.Future


object TestActors {

  case object ResetActor

  case class TestForecastBaseArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends ForecastBaseArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def reset: Receive = {
      case ResetActor => state.clear()
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestForecastPortArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends ForecastPortArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def reset: Receive = {
      case ResetActor => state.clear()
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestLiveArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends LiveArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def reset: Receive = {
      case ResetActor => state.clear()
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestVoyageManifestsActor(override val now: () => SDateLike, expireAfterMillis: Int, snapshotInterval: Int)
    extends VoyageManifestsActor(oneMegaByte, now, expireAfterMillis, Option(snapshotInterval)) {

    def reset: Receive = {
      case ResetActor => state = initialState
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestShiftsActor(override val now: () => SDateLike,
                             override val expireBefore: () => SDateLike) extends ShiftsActor(now, expireBefore) {

    def reset: Receive = {
      case ResetActor =>
        state = initialState
        subscribers = List()
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestFixedPointsActor(override val now: () => SDateLike) extends FixedPointsActor(now) {

    def reset: Receive = {
      case ResetActor =>
        state = initialState
        subscribers = List()
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestStaffMovementsActor(override val now: () => SDateLike,
                                     override val expireBefore: () => SDateLike) extends StaffMovementsActor(now, expireBefore) {

    def reset: Receive = {
      case ResetActor =>
        state = initialState
        subscribers = List()
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestAggregatedArrivalsActor() extends {
    private val portCode = PortCode("LHR")
  } with AggregatedArrivalsActor(ArrivalTable(portCode, PostgresTables)) {
    def reset: Receive = {
      case ResetActor =>
    }

    override def receive: Receive = reset orElse super.receive
  }

  object TestPortStateActor {
    def props(liveStateActor: AskableActorRef,
              forecastStateActor: AskableActorRef,
              now: () => SDateLike,
              liveDaysAhead: Int) =
      Props(new TestPortStateActor(liveStateActor, forecastStateActor, now, liveDaysAhead))
  }

  case class TestPortStateActor(live: AskableActorRef,
                                forecast: AskableActorRef,
                                now: () => SDateLike,
                                liveDaysAhead: Int)
    extends PortStateActor(live, forecast, now, liveDaysAhead) {
    def reset: Receive = {
      case ResetActor => state.clear()
    }

    override def receive: Receive = reset orElse super.receive
  }

  class TestMinutesActor[A, B](now: () => SDateLike,
                               terminals: Iterable[Terminal],
                               lookupPrimary: MinutesLookup[A, B],
                               lookupSecondary: MinutesLookup[A, B],
                               updateMinutes: MinutesUpdate[A, B],
                               resetData: (Terminal, MillisSinceEpoch) => Future[Any]) extends MinutesActor[A, B](now, terminals, lookupPrimary, lookupSecondary, updateMinutes) {
    var terminalDaysUpdated: Set[(Terminal, MillisSinceEpoch)] = Set()

    def myReceive: Receive = {
      case container: MinutesContainer[A, B] =>
        val replyTo = sender()
        addToTerminalDays(container)
        handleUpdatesAndAck(container, replyTo)

      case ResetActor =>
        Future
          .sequence(terminalDaysUpdated.map { case (t, d) => resetData(t, d) })
          .map(_ => Ack)
          .pipeTo(sender())
    }

    private def addToTerminalDays(container: MinutesContainer[A, B]): Unit = {
      groupByTerminalAndDay(container).keys.foreach {
        case (terminal, date) => terminalDaysUpdated = terminalDaysUpdated + ((terminal, date.millisSinceEpoch))
      }
    }

    override def receive: Receive = myReceive orElse super.receive
  }

  class TestPartitionedPortStateActor(flightsActor: AskableActorRef,
                                      queuesActor: AskableActorRef,
                                      staffActor: AskableActorRef,
                                      now: () => SDateLike) extends PartitionedPortStateActor(flightsActor, queuesActor, staffActor, now) {
    def myReceive: Receive = {
      case ResetActor =>
        Future
          .sequence(Seq(flightsActor, queuesActor, staffActor).map(_.ask(ResetActor)))
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
      case ResetActor =>
        log.warn("Received ResetActor request. Deleting all messages & snapshots")
        deleteMessages(Long.MaxValue)
        deleteSnapshot(Long.MaxValue)
    }

    override def receive: Receive = myReceive orElse super.receive
  }

  class TestTerminalDayStaffActor(year: Int,
                                  month: Int,
                                  day: Int,
                                  terminal: Terminal,
                                  now: () => SDateLike) extends TerminalDayStaffActor(year, month, day, terminal, now) {
    def myReceive: Receive = {
      case ResetActor =>
        log.warn("Received ResetActor request. Deleting all messages & snapshots")
        deleteMessages(Long.MaxValue)
        deleteSnapshot(Long.MaxValue)
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
      case ResetActor =>
        log.warn("Received ResetActor request. Deleting all messages & snapshots")
        deleteMessages(Long.MaxValue)
        deleteSnapshot(Long.MaxValue)
        state.clear()
    }

    override def receive: Receive = myReceive orElse super.receive
  }

  object TestCrunchStateActor {
    def props(snapshotInterval: Int,
              name: String,
              portQueues: Map[Terminal, Seq[Queue]],
              now: () => SDateLike,
              expireAfterMillis: Int,
              purgePreviousSnapshots: Boolean): Props = Props(
      new TestCrunchStateActor(
        snapshotInterval,
        name,
        portQueues,
        now,
        expireAfterMillis,
        purgePreviousSnapshots
        )
      )
  }

  case class TestCrunchStateActor(snapshotInterval: Int,
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
      case ResetActor => state = initialState
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  def logMessage(m: Any): String = s"Got this message: ${m.getClass} but not doing anything because this is a test."
}
