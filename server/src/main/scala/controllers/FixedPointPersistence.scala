package controllers

import java.util.UUID

import actors.GetState
import actors.pointInTime.FixedPointsReadActor
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.LoggerFactory
import services.SDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait FixedPointPersistence {
  implicit val timeout: Timeout = Timeout(250 milliseconds)

  val log = LoggerFactory.getLogger(getClass)

  def actorSystem: ActorSystem

  def fixedPointsActor: ActorRef

  def saveFixedPoints(rawFixedPoints: String) = {
    fixedPointsActor ! rawFixedPoints
  }

  def getFixedPoints(pointInTime: MillisSinceEpoch): Future[String] = {
    log.info(s"getFixedPoints($pointInTime)")

    val fixedPointsFuture = fixedPointsActor ? GetState

    val fixedPointsCollected = fixedPointsFuture.collect {
      case fixedPoints: String =>
        log.info(s"Retrieved fixedPoints from actor")
        fixedPoints
    }
    fixedPointsCollected
  }
}
