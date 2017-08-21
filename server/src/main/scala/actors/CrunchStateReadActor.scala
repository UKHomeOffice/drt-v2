package actors

import akka.persistence._
import controllers.GetTerminalCrunch
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared._
import server.protobuf.messages.CrunchState.CrunchStateSnapshotMessage
import services.Crunch.CrunchState
import services.OptimizerCrunchResult

import scala.util.Success

class CrunchStateReadActor(pointInTime: SDateLike, queues: Map[TerminalName, Set[QueueName]]) extends CrunchStateActor(queues) {
  override val receiveRecover: Receive = {
    case SnapshotOffer(md, s) =>
      log.info(s"restoring crunch state $md")
      s match {
        case sm@CrunchStateSnapshotMessage(_, _, _, _) =>
          log.info("matched CrunchStateSnapshotMessage, storing it.")
          state = Option(snapshotMessageToState(sm))
        case somethingElse =>
          log.error(s"Got $somethingElse when trying to restore Crunch State")
      }

    case u =>
      log.warning(s"unexpected message: $u")
  }

  override def receiveCommand: Receive = {
    case SaveSnapshotSuccess =>
      log.info("Saved CrunchState Snapshot")

    case GetFlights =>
      state match {
        case Some(CrunchState(flights, _, _, _)) =>
          sender() ! FlightsWithSplits(flights)
        case None => FlightsNotReady
      }

    case GetPortWorkload =>
      state match {
        case Some(CrunchState(_, workloads, _, _)) =>
          val values = workloads.mapValues(_.mapValues(wl =>
            (wl.map(wlm => WL(wlm._1, wlm._2._2)), wl.map(wlm => Pax(wlm._1, wlm._2._1)))))
          sender() ! values
        case None => WorkloadsNotReady
      }

    case GetTerminalCrunch(terminalName) =>
      val terminalCrunchResults: List[(QueueName, Either[NoCrunchAvailable, CrunchResult])] = state match {
        case Some(CrunchState(_, _, portCrunchResult, crunchFirstMinuteMillis)) =>
          portCrunchResult.getOrElse(terminalName, Map()).map {
            case (queueName, optimiserCRTry) =>
              optimiserCRTry match {
                case Success(OptimizerCrunchResult(deskRecs, waitTimes)) =>
                  (queueName, Right(CrunchResult(crunchFirstMinuteMillis, 60000, deskRecs, waitTimes)))
                case _ =>
                  (queueName, Left(NoCrunchAvailable()))
              }
          }.toList
        case None => List[(QueueName, Either[NoCrunchAvailable, CrunchResult])]()
      }
      sender() ! terminalCrunchResults
  }

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    val recovery = Recovery(
      fromSnapshot = criteria,
      replayMax = snapshotInterval)
    log.info(s"recovery: $recovery")
    recovery
  }
}