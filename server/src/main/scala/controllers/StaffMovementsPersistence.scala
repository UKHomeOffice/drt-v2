package controllers

import actors.{GetState, StaffMovementsActor}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout
import spatutorial.shared.StaffMovement

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.collection.immutable.Seq

trait StaffMovementsPersistence {
  implicit val timeout: Timeout = Timeout(5 seconds)

  def actorSystem: ActorSystem

  def staffMovementsActor: ActorRef = actorSystem.actorOf(Props(classOf[StaffMovementsActor]))

  def saveStaffMovements(staffMovements: Seq[StaffMovement]) = {
    staffMovementsActor ! staffMovements
  }

  def getStaffMovements(): Future[Seq[StaffMovement]] = {
    val res: Future[Any] = staffMovementsActor ? GetState

    val eventualStaffMovements = res.collect {
      case staffMovements: Seq[StaffMovement] =>
        actorSystem.log.info(s"Retrieved staff movements from actor: $staffMovements")
        staffMovements
    }
    eventualStaffMovements
  }
}
