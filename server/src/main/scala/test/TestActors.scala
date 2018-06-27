package test

import actors._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.SDateLike
import services.{ForecastBaseArrivalsActor, ForecastPortArrivalsActor, LiveArrivalsActor}

object TestActors {

  case object ResetActor

  case class TestForecastBaseArrivalsActor(now: () => SDateLike, expireAfterMillis: Long)
    extends ForecastBaseArrivalsActor(now, expireAfterMillis) {

    def reset: Receive = {
      case ResetActor =>
        state = initialState
    }

    override def receiveRecover: Receive = {
      case m =>
        log.info(s"Got this message: $m but not doing anything because this is a test.")
    }
    override def receiveCommand: Receive = {
      reset orElse super.receiveCommand
    }
  }

  case class TestForecastPortArrivalsActor(now: () => SDateLike, expireAfterMillis: Long)
    extends  ForecastPortArrivalsActor(now, expireAfterMillis){

    def reset: Receive = {
      case ResetActor =>
        state = initialState
    }

    override def receiveRecover: Receive = {
      case m =>
        log.info(s"Got this message: $m but not doing anything because this is a test.")
    }
    override def receiveCommand: Receive = {
      reset orElse super.receiveCommand
    }
  }

  case class TestLiveArrivalsActor(now: () => SDateLike, expireAfterMillis: Long)
    extends  LiveArrivalsActor(now, expireAfterMillis){

    def reset: Receive = {
      case ResetActor =>
        state = initialState
    }

    override def receiveRecover: Receive = {
      case m =>
        log.info(s"Got this message: $m but not doing anything because this is a test.")
    }
    override def receiveCommand: Receive = {
      reset orElse super.receiveCommand
    }
  }

  case class TestVoyageManifestsActor(now: () => SDateLike, expireAfterMillis: Long, snapshotInterval: Int)
    extends  VoyageManifestsActor(now, expireAfterMillis, snapshotInterval){

    def reset: Receive = {
      case ResetActor =>
        state = initialState
    }

    override def receiveRecover: Receive = {
      case m =>
        log.info(s"Got this message: $m but not doing anything because this is a test.")
    }
    override def receiveCommand: Receive = {
      reset orElse super.receiveCommand
    }
  }

  case class TestShiftsActor() extends  ShiftsActor{

    def reset: Receive = {
      case ResetActor =>
        state = initialState
    }

    override def receiveRecover: Receive = {
      case m =>
        log.info(s"Got this message: $m but not doing anything because this is a test.")
    }
    override def receiveCommand: Receive = {
      reset orElse super.receiveCommand
    }
  }

  case class TestFixedPointsActor() extends  FixedPointsActor{

    def reset: Receive = {
      case ResetActor =>
        state = initialState
    }

    override def receiveRecover: Receive = {
      case m =>
        log.info(s"Got this message: $m but not doing anything because this is a test.")
    }
    override def receiveCommand: Receive = {
      reset orElse super.receiveCommand
    }
  }

  case class TestStaffMovementsActor() extends  StaffMovementsActor{

    def reset: Receive = {
      case ResetActor =>
        state = initialState
    }

    override def receiveRecover: Receive = {
      case m =>
        log.info(s"Got this message: $m but not doing anything because this is a test.")
    }
    override def receiveCommand: Receive = {
      reset orElse super.receiveCommand
    }
  }

  case class TestCrunchStateActor(override val snapshotInterval: Int,
                                   name: String,
                                   portQueues: Map[TerminalName, Seq[QueueName]],
                                   now: () => SDateLike,
                                   expireAfterMillis: Long,
                                   purgePreviousSnapshots: Boolean) extends  CrunchStateActor(snapshotInterval, name, portQueues, now,expireAfterMillis, purgePreviousSnapshots) {

    def reset: Receive = {
      case ResetActor =>
        state = initialState
    }

    override def receiveRecover: Receive = {
      case m =>
        log.info(s"Got this message: $m but not doing anything because this is a test.")
    }
    override def receiveCommand: Receive = {
      reset orElse super.receiveCommand
    }
  }

}
