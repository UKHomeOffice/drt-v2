package controllers

import java.util.UUID

import actors.pointInTime.ShiftsReadActor
import actors.{DrtStaticParameters, GetState}
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.pattern._
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.ShiftAssignments
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

trait ShiftPersistence {
  implicit val timeout: Timeout = Timeout(250 milliseconds)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def actorSystem: ActorSystem

  def shiftsActor: ActorRef

  def getShifts(maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments] = maybePointInTime match {
    case None =>
      shiftsActor.ask(GetState)
        .map { case sa: ShiftAssignments => sa }
        .recoverWith { case _ => Future(ShiftAssignments.empty) }

    case Some(millis) =>
      val date = SDate(millis)

      val actorName = "shifts-read-actor-" + UUID.randomUUID().toString
      val shiftsReadActor: ActorRef = actorSystem.actorOf(ShiftsReadActor.props(date, DrtStaticParameters.time48HoursAgo(() => date)), actorName)

      shiftsReadActor.ask(GetState)
        .map { case sa: ShiftAssignments =>
          shiftsReadActor ! PoisonPill
          sa
        }
        .recoverWith {
          case _ =>
            shiftsReadActor ! PoisonPill
            Future(ShiftAssignments.empty)
        }
  }
}
