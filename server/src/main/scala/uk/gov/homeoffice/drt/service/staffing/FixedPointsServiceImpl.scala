package uk.gov.homeoffice.drt.service.staffing

import actors.persistent.staffing.FixedPointsActor.SetFixedPoints
import actors.persistent.staffing.FixedPointsReadActor
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{FixedPointAssignments, StaffAssignmentLike}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

object FixedPointsServiceImpl {
  def pitActor(implicit system: ActorSystem): SDateLike => ActorRef = pointInTime => {
    val actorName = "fixed-points-read-actor-" + UUID.randomUUID().toString
    system.actorOf(Props(
      classOf[FixedPointsReadActor],
      pointInTime,
      () => SDate.now(),
    ), actorName)
  }
}

case class FixedPointsServiceImpl(liveFixedPointsActor: ActorRef,
                                  fixedPointsWriteActor: ActorRef,
                                  pointInTimeActor: SDateLike => ActorRef,
                                 )
                                 (implicit timeout: Timeout, ec: ExecutionContext) extends FixedPointsService {

  override def fixedPoints(maybePointInTime: Option[MillisSinceEpoch]): Future[FixedPointAssignments] = {
    maybePointInTime match {
      case None =>
        liveFixedPointsForDate()

      case Some(millis) =>
        fixedPointsForPointInTime(SDate(millis))
    }
  }

  private def liveFixedPointsForDate(): Future[FixedPointAssignments] =
    liveFixedPointsActor.ask(GetState)
      .map { case sa: FixedPointAssignments => sa }

  private def fixedPointsForPointInTime(pointInTime: SDateLike): Future[FixedPointAssignments] = {
    val fixedPointsReadActor: ActorRef = pointInTimeActor(pointInTime)

    fixedPointsReadActor.ask(GetState)
      .map { case sa: FixedPointAssignments =>
        fixedPointsReadActor ! PoisonPill
        sa
      }
      .recoverWith {
        case t =>
          fixedPointsReadActor ! PoisonPill
          throw t
      }
  }

  override def updateFixedPoints(assignments: Seq[StaffAssignmentLike]): Unit = {
    fixedPointsWriteActor ! SetFixedPoints(assignments)
  }
}
