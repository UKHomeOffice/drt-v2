package controllers

import java.util.UUID

import actors.GetState
import actors.pointInTime.ShiftsReadActor
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout
import drt.shared.Crunch.MillisSinceEpoch
import org.slf4j.LoggerFactory
import services.SDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait ShiftPersistence {
  implicit val timeout: Timeout = Timeout(250 milliseconds)

  val log = LoggerFactory.getLogger(getClass)

  def actorSystem: ActorSystem

  def shiftsActor: ActorRef

  def saveShifts(rawShifts: String) = {
      shiftsActor ! rawShifts
  }

  def getShifts(pointInTime: MillisSinceEpoch): Future[String] = {
    log.info(s"getShifts($pointInTime)")
    val actor: AskableActorRef = if (pointInTime > 0) {
      log.info(s"Creating ShiftsReadActor for $pointInTime")
      val shiftsReadActorProps = Props(classOf[ShiftsReadActor], SDate(pointInTime))
      actorSystem.actorOf(shiftsReadActorProps, "shiftsReadActor" + UUID.randomUUID().toString)
    } else shiftsActor

    val shiftsFuture = actor ? GetState

    val shiftsCollected = shiftsFuture.collect {
      case shifts: String =>
        log.info(s"Retrieved shifts from actor ($actor): $shifts")
        shifts
    }
    shiftsCollected
  }
}
