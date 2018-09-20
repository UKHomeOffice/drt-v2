package controllers

import java.util.UUID

import actors.pointInTime.StaffMovementsReadActor
import actors.{GetState, StaffMovements}
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern._
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.StaffMovement
import org.slf4j.LoggerFactory
import services.SDate
import services.graphstages.Crunch

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait StaffMovementsPersistence {
  implicit val timeout: Timeout = Timeout(5 seconds)

  val log = LoggerFactory.getLogger(getClass)

  def actorSystem: ActorSystem

  def staffMovementsActor: ActorRef

  def saveStaffMovements(staffMovements: Seq[StaffMovement]) = {
    actorSystem.log.info(s"Sending StaffMovements to staffMovementsActor")
    staffMovementsActor ! StaffMovements(staffMovements)
  }

  def getStaffMovements(pointInTime: MillisSinceEpoch): Future[Seq[StaffMovement]] = {
    val forDate = SDate(pointInTime)

    log.info(s"getStaffMovements(${forDate.toISOString()})")

    val staffMovementsFuture: Future[Seq[StaffMovement]] = if (forDate.millisSinceEpoch < Crunch.getLocalLastMidnight(SDate.now).millisSinceEpoch) {
      val actorName = "staff-movements-read-actor-" + UUID.randomUUID().toString
      val staffMovementsReadActor: ActorRef = actorSystem.actorOf(Props(classOf[StaffMovementsReadActor], forDate), actorName)

      staffMovementsReadActor.ask(GetState)
        .map { case StaffMovements(sm) =>
          staffMovementsReadActor ! PoisonPill
          sm
        }.recoverWith {
        case _ =>
          staffMovementsReadActor ! PoisonPill
          Future(Seq())
      }
    } else {
      staffMovementsActor.ask(GetState)
        .map { case StaffMovements(sm) => sm }.recoverWith { case _ => Future(Seq()) }
    }

    val eventualStaffMovements = staffMovementsFuture.collect {
      case Nil =>
        log.info(s"Got no movements")
        List()
      case sm: Seq[StaffMovement] =>
        actorSystem.log.info(s"Retrieved staff movements from actor for ${forDate.toISOString()}")
        sm
    }
    eventualStaffMovements
  }
}
