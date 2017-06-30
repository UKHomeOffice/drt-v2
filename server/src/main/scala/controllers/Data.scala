package controllers

import actors.ShiftsActor
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.Materializer
import com.google.inject.Inject
import drt.staff.ImportStaff
import play.api.{Configuration, Environment}
import play.api.mvc.{Action, Controller}

import scala.concurrent.ExecutionContext


class Data @Inject()(override val actorSystem: ActorSystem) extends Controller with ShiftPersistence {

  def saveStaff() = Action {
    implicit request =>
      def shiftsActor: ActorRef = actorSystem.actorOf(Props(classOf[ShiftsActor]))

      request.body.asJson.flatMap(ImportStaff.staffJsonToShifts) match {
        case Some(shiftsString) =>
          shiftsActor ! shiftsString
          Created
        case _ =>
          BadRequest("{\"error\": \"Unable to parse data\"}")
      }
  }
}
