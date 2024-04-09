package uk.gov.homeoffice.drt.service.staffing

import actors.persistent.staffing.{FixedPointsReadActor, SetFixedPoints}
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{FixedPointAssignments, StaffAssignmentLike}
import org.slf4j.LoggerFactory
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
  private val log = LoggerFactory.getLogger(getClass)

  import uk.gov.homeoffice.drt.time.SDate.implicits.sdateFromMillisLocal

  override def fixedPoints(maybePointInTime: Option[MillisSinceEpoch]): Future[FixedPointAssignments] = {
    maybePointInTime match {
      case None =>
        liveFixedPointsForDate()

      case Some(millis) =>
        fixedPointsForPointInTime(SDate(millis))
    }
  }

  private def liveFixedPointsForDate(): Future[FixedPointAssignments] = {
    liveFixedPointsActor.ask(GetState)
      .map { case sa: FixedPointAssignments => sa }
      .recoverWith {
        case t =>
          log.error(s"Error getting fixedPoints: ${t.getMessage}")
          Future(FixedPointAssignments.empty)
      }
  }

  private def fixedPointsForPointInTime(pointInTime: SDateLike): Future[FixedPointAssignments] = {
    val fixedPointsReadActor: ActorRef = pointInTimeActor(pointInTime)

    fixedPointsReadActor.ask(GetState)
      .map { case sa: FixedPointAssignments =>
        fixedPointsReadActor ! PoisonPill
        sa
      }
      .recoverWith {
        case _ =>
          fixedPointsReadActor ! PoisonPill
          Future(FixedPointAssignments.empty)
      }
  }

  override def updateFixedPoints(assignments: Seq[StaffAssignmentLike]): Unit = {
    fixedPointsWriteActor ! SetFixedPoints(assignments)
  }
}
