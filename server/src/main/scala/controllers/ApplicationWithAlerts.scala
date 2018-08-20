package controllers

import actors.{DeleteAlerts, GetState}
import akka.actor._
import akka.pattern._
import akka.util.Timeout
import drt.shared.Alert
import org.joda.time.DateTime
import play.api.libs.json.{JodaReads, Json}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

trait ApplicationWithAlerts {
  self: Application =>
  val pattern = "yyyy-MM-dd HH:mm:ss"
  implicit val dateRead = JodaReads.jodaDateReads(pattern)
  implicit val alertReads = Json.reads[AlertMessage]

  def addAlert = Action {
    implicit request =>

      request.body.asJson.map { json =>
        val alertMessage = json.as[AlertMessage]
        ctrl.alertsActor ! Alert(
          alertMessage.title,
          alertMessage.message,
          alertMessage.expires.getMillis,
          createdAt = DateTime.now.getMillis
        )
        Ok("done!")
      }.getOrElse {
        BadRequest("{\"error\": \"Unable to parse data\"}")
      }
  }

  def deleteAlerts = Action.async {
    val futureAlerts = ctrl.alertsActor.ask(DeleteAlerts)(new Timeout(5 second))
    futureAlerts.map(s =>
      Ok(s.toString)
    )
  }
}

case class AlertMessage(title: String, message: String, expires: DateTime)