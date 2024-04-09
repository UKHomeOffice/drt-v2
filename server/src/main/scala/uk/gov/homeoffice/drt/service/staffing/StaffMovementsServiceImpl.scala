package uk.gov.homeoffice.drt.service.staffing

import actors.persistent.staffing.{AddStaffMovements, RemoveStaffMovements, StaffMovementsReadActor}
import akka.Done
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.{StaffMovement, StaffMovements}
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.time.MilliDate.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

object StaffMovementsServiceImpl {
  def pitActor(implicit system: ActorSystem): SDateLike => ActorRef = pointInTime => {
    val actorName = "movements-read-actor-" + UUID.randomUUID().toString
    val expireBefore = () => pointInTime.addDays(-1)
    system.actorOf(Props(
      classOf[StaffMovementsReadActor],
      pointInTime,
      expireBefore,
    ), actorName)
  }
}

case class StaffMovementsServiceImpl(liveMovementsActor: ActorRef,
                                     movementsWriteActor: ActorRef,
                                     pitActor: SDateLike => ActorRef,
                                    )
                                    (implicit timeout: Timeout, ec: ExecutionContext) extends StaffMovementsService {
  private val log = LoggerFactory.getLogger(getClass)

  override def movementsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[Seq[StaffMovement]] = {
    maybePointInTime match {
      case None =>
        liveMovementsForDate(date)

      case Some(millis) =>
        movementsForPointInTime(SDate(millis))
    }
  }

  private def liveMovementsForDate(date: LocalDate): Future[Seq[StaffMovement]] = {
    liveMovementsActor
      .ask(GetState)
      .mapTo[StaffMovements]
      .map(_.forDay(date)(ld => SDate(ld)))
      .recoverWith {
        case t =>
          log.error(s"Error getting movements for $date: ${t.getMessage}")
          Future(Seq.empty)
      }
  }

  private def movementsForPointInTime(pointInTime: SDateLike): Future[Seq[StaffMovement]] = {
    val movementsReadActor = pitActor(pointInTime)

    movementsReadActor.ask(GetState)
      .mapTo[StaffMovements]
      .map { mm =>
        movementsReadActor ! PoisonPill
        mm.forDay(pointInTime.toLocalDate)(ld => SDate(ld))
      }
      .recoverWith {
        case _ =>
          movementsReadActor ! PoisonPill
          Future(Seq.empty)
      }
  }

  override def addMovements(movements: List[StaffMovement]): Future[Done.type] = {
    movementsWriteActor.ask(AddStaffMovements(movements)).map(_ => Done)
  }

  override def removeMovements(movementUuid: String): Unit = {
    movementsWriteActor ! RemoveStaffMovements(movementUuid)
  }
}
