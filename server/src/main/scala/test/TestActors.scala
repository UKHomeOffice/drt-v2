package test

import actors.Sizes.oneMegaByte
import actors._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.SDateLike
import slickdb.ArrivalTable


object TestActors {

  case object ResetActor

  case class TestForecastBaseArrivalsActor(now: () => SDateLike, expireAfterMillis: Long)
    extends ForecastBaseArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def reset: Receive = {
      case ResetActor =>
        state.clear()
        log.info("Resetting state")
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestForecastPortArrivalsActor(now: () => SDateLike, expireAfterMillis: Long)
    extends ForecastPortArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def reset: Receive = {
      case ResetActor =>
        state.clear()
        log.info("Resetting state")
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestLiveArrivalsActor(now: () => SDateLike, expireAfterMillis: Long)
    extends LiveArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def reset: Receive = {
      case ResetActor =>
        state.clear()
        log.info("Resetting state")
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestVoyageManifestsActor(now: () => SDateLike, expireAfterMillis: Long, snapshotInterval: Int)
    extends VoyageManifestsActor(oneMegaByte, now, expireAfterMillis, Option(snapshotInterval)) {

    def reset: Receive = {
      case ResetActor =>
        state = initialState
        log.info("Resetting state")
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
        log.info("Resetting state")
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestFixedPointsActor(now: () => SDateLike) extends FixedPointsActor(now) {

    def reset: Receive = {
      case ResetActor =>
        state = initialState
        subscribers = List()
        log.info("Resetting state")
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
        log.info("Resetting state")
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestAggregatedArrivalsActor() extends AggregatedArrivalsActor("LHR", ArrivalTable("LHR", PostgresTables)) {
    def reset: Receive = {
      case ResetActor => log.info("Resetting state (noop)")
    }

    override def receive: Receive = reset orElse super.receive
  }

  case class TestCrunchStateActor(snapshotInterval: Int,
                                  name: String,
                                  portQueues: Map[TerminalName, Seq[QueueName]],
                                  now: () => SDateLike,
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
      acceptFullStateUpdates = false,
      forecastMaxMillis = () => now().addDays(2).millisSinceEpoch) {

    def reset: Receive = {
      case ResetActor =>
        log.info("Resetting state")
        state = initialState
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  def logMessage(m: Any): String = s"Got this message: ${m.getClass} but not doing anything because this is a test."
}
