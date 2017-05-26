package controllers

import actors.{GetState, FixedPointsActor}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

trait FixedPointPersistence {
  implicit val timeout: Timeout = Timeout(5 seconds)

  def actorSystem: ActorSystem

  def fixedPointsActor: ActorRef = actorSystem.actorOf(Props(classOf[FixedPointsActor]))

  def saveFixedPoints(rawFixedPoints: String) = {
    fixedPointsActor ! rawFixedPoints
  }

  def getFixedPoints(): Future[String] = {
    val res: Future[Any] = fixedPointsActor ? GetState

    val fixedPointsFuture = res.collect {
      case fixedPoints: String =>
        actorSystem.log.info(s"Retrieved fixedPoints from actor: $fixedPoints")
        fixedPoints
    }
    fixedPointsFuture
  }
}
