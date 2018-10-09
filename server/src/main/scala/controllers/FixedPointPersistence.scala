package controllers

import java.util.UUID

import actors.{DrtStaticParameters, GetState}
import actors.pointInTime.FixedPointsReadActor
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern._
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FixedPointAssignments
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait FixedPointPersistence {
  implicit val timeout: Timeout = Timeout(250 milliseconds)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def actorSystem: ActorSystem

  def fixedPointsActor: ActorRef

  def saveFixedPoints(fixedPoints: FixedPointAssignments): Unit = {
    log.info(s"Sending fixed points to actor: $fixedPoints")
    fixedPointsActor ! fixedPoints
  }

  def getFixedPoints(maybePointInTime: Option[MillisSinceEpoch]): Future[FixedPointAssignments] = maybePointInTime match {
    case None =>
      log.info(s"getFixedPoints(None)")
      fixedPointsActor.ask(GetState)
        .map { case sa: FixedPointAssignments => sa }
        .recoverWith { case _ => Future(FixedPointAssignments.empty) }

    case Some(millis) =>
      val date = SDate(millis)
      log.info(s"getFixedPoints(${date.toISOString()})")

      val actorName = "fixed-points-read-actor-" + UUID.randomUUID().toString
      val fixedPointsReadActor: ActorRef = actorSystem.actorOf(Props(classOf[FixedPointsReadActor], date), actorName)

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
}
