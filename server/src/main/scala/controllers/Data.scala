package controllers

import actors.ShiftsActorBase
import akka.actor.{ActorRef, ActorSystem, Props}
import com.google.inject.Inject
import drt.staff.ImportStaff
import play.api.mvc.{Action, Controller}


class Data @Inject()(val system: ActorSystem) extends Controller {

  def saveStaff() = Action {
    implicit request =>
      def shiftsActor: ActorRef = system.actorOf(Props(classOf[ShiftsActorBase]))

      request.body.asJson.flatMap(ImportStaff.staffJsonToShifts) match {
        case Some(shiftsString) =>
          shiftsActor ! shiftsString
          Created
        case _ =>
          BadRequest("{\"error\": \"Unable to parse data\"}")
      }
  }
}
