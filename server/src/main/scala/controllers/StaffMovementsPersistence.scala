package controllers

import java.util.UUID

import actors.pointInTime.StaffMovementsReadActor
import actors.{GetState, StaffMovements}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout
import drt.shared.Crunch.MillisSinceEpoch
import drt.shared.StaffMovement
import org.slf4j.LoggerFactory
import services.SDate

import scala.collection.immutable.Seq
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
    log.info(s"getStaffMovements($pointInTime)")
    val actor: AskableActorRef = if (pointInTime > 0) {
      log.info(s"Creating StaffMovementsReadActor for $pointInTime")
      val staffMovementsReadActorProps = Props(classOf[StaffMovementsReadActor], SDate(pointInTime))
      actorSystem.actorOf(staffMovementsReadActorProps, "staffMovementsReadActor" + UUID.randomUUID().toString)
    } else staffMovementsActor

    val staffMovementsFuture = actor ? GetState

    val eventualStaffMovements = staffMovementsFuture.collect {
      case StaffMovements(sm) =>
        actorSystem.log.info(s"Retrieved staff movements from actor: $sm")
        sm
      case _ => List()
    }
    eventualStaffMovements
  }
}
