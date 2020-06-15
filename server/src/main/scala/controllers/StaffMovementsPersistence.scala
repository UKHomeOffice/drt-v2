package controllers

import java.util.UUID

import actors._
import actors.pointInTime.StaffMovementsReadActor
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
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
}
