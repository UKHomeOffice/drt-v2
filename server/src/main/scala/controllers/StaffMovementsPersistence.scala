package controllers

import java.util.UUID

import actors._
import actors.pointInTime.StaffMovementsReadActor
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern._
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.StaffMovement
import org.slf4j.LoggerFactory
import services.SDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait StaffMovementsPersistence {
  implicit val timeout: Timeout = Timeout(5 seconds)

  val log = LoggerFactory.getLogger(getClass)

  def actorSystem: ActorSystem

  def staffMovementsActor: ActorRef

  def addStaffMovements(movementsToAdd: Seq[StaffMovement]): Unit = {
    actorSystem.log.info(s"Sending StaffMovements to staffMovementsActor")
    staffMovementsActor ! AddStaffMovements(movementsToAdd)
  }

  def removeStaffMovements(movementsToRemove: UUID): Unit = {
    actorSystem.log.info(s"Sending StaffMovements to staffMovementsActor")
    staffMovementsActor ! RemoveStaffMovements(movementsToRemove)
  }

  def getStaffMovements(maybePointInTime: Option[MillisSinceEpoch]): Future[Seq[StaffMovement]] = {
    val staffMovementsFuture = maybePointInTime match {
      case None =>
        log.info(s"getStaffMovements(None)")
        staffMovementsActor.ask(GetState)
          .map { case StaffMovements(movements) => movements }
          .recoverWith { case _ => Future(Seq()) }

      case Some(millis) =>
        val date = SDate(millis)
        log.info(s"getStaffMovements(${date.toISOString()})")

        val actorName = "staff-movements-read-actor-" + UUID.randomUUID().toString
        val staffMovementsReadActor: ActorRef = actorSystem.actorOf(Props(classOf[StaffMovementsReadActor], date, DrtStaticParameters.expire48HoursAgo(() => date)), actorName)

        staffMovementsReadActor.ask(GetState)
          .map { case StaffMovements(movements) =>
            staffMovementsReadActor ! PoisonPill
            movements
          }
          .recoverWith {
            case _ =>
              staffMovementsReadActor ! PoisonPill
              Future(Seq())
          }
    }

    val eventualStaffMovements = staffMovementsFuture.collect {
      case Nil =>
        log.info(s"Got no movements")
        List()
      case sm: Seq[StaffMovement] => sm
    }

    eventualStaffMovements
  }
}
