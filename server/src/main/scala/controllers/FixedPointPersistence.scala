package controllers

import actors.GetState
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern._
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FixedPointAssignments
import org.slf4j.{Logger, LoggerFactory}

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

  def getFixedPoints(pointInTime: MillisSinceEpoch): Future[FixedPointAssignments] = {
    log.info(s"getFixedPoints($pointInTime)")

    val fixedPointsFuture: Future[FixedPointAssignments] = fixedPointsActor ? GetState map {
      case sa: FixedPointAssignments => sa
      case _ => FixedPointAssignments(Seq())
    }

    fixedPointsFuture
  }
}
