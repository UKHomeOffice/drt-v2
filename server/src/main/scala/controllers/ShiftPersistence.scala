package controllers

import actors.GetState
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern._
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.ShiftAssignments
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

trait ShiftPersistence {
  implicit val timeout: Timeout = Timeout(250 milliseconds)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def actorSystem: ActorSystem

  def shiftsActor: ActorRef

  def getShifts(pointInTime: MillisSinceEpoch): Future[ShiftAssignments] = {
    log.info(s"getShifts($pointInTime)")

    val shiftsFuture: Future[ShiftAssignments] = shiftsActor ? GetState map {
      case sa: ShiftAssignments => sa
      case _ => ShiftAssignments(Seq())
    }

    shiftsFuture
  }
}
