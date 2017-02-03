package controllers

import actors.{GetState, ShiftsActor}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import ExecutionContext.Implicits.global
import akka.pattern._

import scala.concurrent.Future

trait ShiftPersistence {
  implicit val timeout: Timeout = Timeout(5 seconds)

  def actorSystem: ActorSystem

  def shiftsActor: ActorRef = actorSystem.actorOf(Props(classOf[ShiftsActor]))

  def saveShifts(rawShifts: String) = {
    shiftsActor ! rawShifts
  }

  def getShifts(): Future[String] = {
    val res: Future[Any] = shiftsActor ? GetState

    val shiftsFuture = res.collect {
      case shifts: String =>
        actorSystem.log.info(s"Retrieved shifts from actor: $shifts")
        shifts
    }
    shiftsFuture
  }
}
