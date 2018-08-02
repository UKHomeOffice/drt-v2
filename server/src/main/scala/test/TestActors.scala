package test

import actors._
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.SDateLike


object TestActors {

  case object ResetActor

  case class TestForecastBaseArrivalsActor(now: () => SDateLike, expireAfterMillis: Long)
    extends ForecastBaseArrivalsActor(now, expireAfterMillis) {

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
    extends ForecastPortArrivalsActor(now, expireAfterMillis) {

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
    extends LiveArrivalsActor(now, expireAfterMillis) {

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
    extends VoyageManifestsActor(now, expireAfterMillis, snapshotInterval) {

    def reset: Receive = {
      case ResetActor =>
        state = initialState
    }

    override def receiveRecover: Receive = {
      case m => log.info(logMessage(m))
    }

    override def receiveCommand: Receive = reset orElse super.receiveCommand
  }

  case class TestShiftsActor() extends ShiftsActor {

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

  case class TestFixedPointsActor() extends FixedPointsActor {

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

  case class TestStaffMovementsActor() extends StaffMovementsActor {

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

  case class TestCrunchStateActor(override val snapshotInterval: Int,
                                  name: String,
                                  portQueues: Map[TerminalName, Seq[QueueName]],
                                  now: () => SDateLike,
                                  expireAfterMillis: Long,
                                  purgePreviousSnapshots: Boolean) extends CrunchStateActor(snapshotInterval, 1024 * 1024, name, portQueues, now, expireAfterMillis, purgePreviousSnapshots) {

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
