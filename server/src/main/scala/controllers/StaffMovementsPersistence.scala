package controllers

import java.util.UUID

import actors.pointInTime.StaffMovementsReadActor
import actors.{GetState, StaffMovements}
import akka.actor.{ActorRef, ActorSystem, Props}
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

  def saveStaffMovements(staffMovements: Seq[StaffMovement]) = {
    actorSystem.log.info(s"Sending StaffMovements to staffMovementsActor")
    staffMovementsActor ! StaffMovements(staffMovements)
  }

  def getStaffMovements(pointInTime: MillisSinceEpoch): Future[Seq[StaffMovement]] = {
    val forDate = SDate(pointInTime)

    log.info(s"getStaffMovements(${forDate.toISOString()})")

    val actorName = "staff-movements-read-actor-" + UUID.randomUUID().toString
    val staffMovementsReadActor: AskableActorRef = actorSystem.actorOf(Props(classOf[StaffMovementsReadActor], forDate), actorName)

    val staffMovementsFuture: Future[Seq[StaffMovement]] = staffMovementsReadActor.ask(GetState)
      .map { case StaffMovements(sm) => sm }.recoverWith { case _ => Future(Seq()) }

    val eventualStaffMovements = staffMovementsFuture.collect {
      case Nil =>
        log.info(s"Got no movements")
        List()
      case sm: Seq[StaffMovement] =>
        log.info(s"Got these movements: $sm")
        actorSystem.log.info(s"Retrieved staff movements from actor for ${forDate.toISOString()}")
        sm
    }
    eventualStaffMovements
  }
}
