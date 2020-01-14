package test

import actors.Sizes.oneMegaByte
import actors._
import akka.actor.Props
import akka.pattern.AskableActorRef
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{PortCode, SDateLike}
import slickdb.ArrivalTable


object TestActors {

  case object ResetActor

  case class TestForecastBaseArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Long)
    extends ForecastBaseArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def reset: Receive = {
      case ResetActor => state.clear()
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestForecastPortArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Long)
    extends ForecastPortArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def reset: Receive = {
      case ResetActor => state.clear()
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestLiveArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Long)
    extends LiveArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def reset: Receive = {
      case ResetActor => state.clear()
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestVoyageManifestsActor(override val now: () => SDateLike, expireAfterMillis: Long, snapshotInterval: Int)
    extends VoyageManifestsActor(oneMegaByte, now, expireAfterMillis, Option(snapshotInterval)) {

    def reset: Receive = {
      case ResetActor => state = initialState
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestShiftsActor(override val now: () => SDateLike, override val expireBefore: () => SDateLike) extends ShiftsActor(now, expireBefore) {

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

  case class TestStaffMovementsActor(override val now: () => SDateLike, override val expireBefore: () => SDateLike) extends StaffMovementsActor(now, expireBefore) {

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
      case ResetActor => Unit
    }

    override def receive: Receive = reset orElse super.receive
  }

  object TestPortStateActor {
    def props(liveStateActor: AskableActorRef, forecastStateActor: AskableActorRef, now: () => SDateLike, liveDaysAhead: Int) =
      Props(new TestPortStateActor(liveStateActor, forecastStateActor, now, liveDaysAhead))
  }

  case class TestPortStateActor(live: AskableActorRef, forecast: AskableActorRef, now: () => SDateLike, liveDaysAhead: Int)
    extends PortStateActor(live, forecast, now, liveDaysAhead) {
    def reset: Receive = {
      case ResetActor => state.clear()
    }

    override def receive: Receive = reset orElse super.receive
  }

  object TestCrunchStateActor {
    def props(snapshotInterval: Int,
              name: String,
              portQueues: Map[Terminal, Seq[Queue]],
              now: () => SDateLike,
              expireAfterMillis: Long,
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
                                  expireAfterMillis: Long,
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
