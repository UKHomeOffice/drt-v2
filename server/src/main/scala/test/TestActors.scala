package test

import actors.Sizes.oneMegaByte
import actors._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.SDateLike


object TestActors {

  case object ResetActor

  case class TestForecastBaseArrivalsActor(now: () => SDateLike, expireAfterMillis: Long)
    extends ForecastBaseArrivalsActor(oneMegaByte, now, expireAfterMillis) {

    def reset: Receive = {
      case ResetActor =>
        state = initialState
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
        state = initialState
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
        state = initialState
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestVoyageManifestsActor(now: () => SDateLike, expireAfterMillis: Long, snapshotInterval: Int)
    extends VoyageManifestsActor(oneMegaByte, now, expireAfterMillis, snapshotInterval) {

    def reset: Receive = {
      case ResetActor =>
        state = initialState
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestShiftsActor(now: () => SDateLike, expireAfterMillis: Long) extends ShiftsActor(now, expireAfterMillis) {

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

  case class TestFixedPointsActor(now: () => SDateLike) extends FixedPointsActor(now) {

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

  case class TestStaffMovementsActor(now: () => SDateLike, expireAfterMillis: Long) extends StaffMovementsActor(now, expireAfterMillis) {

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

  case class TestCrunchStateActor(snapshotInterval: Int,
                                  name: String,
                                  portQueues: Map[TerminalName, Seq[QueueName]],
                                  now: () => SDateLike,
                                  expireAfterMillis: Long,
                                  purgePreviousSnapshots: Boolean) extends CrunchStateActor(None, oneMegaByte, name, portQueues, now, expireAfterMillis, purgePreviousSnapshots) {

    def reset: Receive = {
      case ResetActor =>
        state = initialState
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  def logMessage(m: Any): String = s"Got this message: ${m.getClass} but not doing anything because this is a test."
}
