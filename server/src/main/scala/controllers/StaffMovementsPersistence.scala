package controllers

import actors.{GetState, StaffMovements}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern._
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.StaffMovement
import org.slf4j.LoggerFactory

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

    val staffMovementsFuture = staffMovementsActor ? GetState

    val eventualStaffMovements = staffMovementsFuture.collect {
      case StaffMovements(sm) =>
        actorSystem.log.info(s"Retrieved staff movements from actor")
        sm
      case _ => List()
    }
    eventualStaffMovements
  }
}
