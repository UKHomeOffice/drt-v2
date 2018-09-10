package controllers

import actors.GetState
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern._
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.StaffAssignments
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

  def getShifts(pointInTime: MillisSinceEpoch): Future[StaffAssignments] = {
    log.info(s"getShifts($pointInTime)")

    val shiftsFuture: Future[StaffAssignments] = shiftsActor ? GetState map {
      case sa: StaffAssignments => sa
      case _ => StaffAssignments(Seq())
    }

    shiftsFuture
  }
}
