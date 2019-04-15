package controllers

import actors.DeleteAlerts
import akka.pattern._
import akka.util.Timeout
import drt.shared.Alert
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.mvc.{Action, AnyContent}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait ApplicationWithAlerts {
  self: Application =>
  val pattern = "yyyy-MM-dd HH:mm:ss"
  implicit val dateRead: Reads[DateTime] = JodaReads.jodaDateReads(pattern)
  implicit val alertMessageReads: Reads[AlertMessage] = Json.reads[AlertMessage]

  def addAlert(): Action[AnyContent] = Action.async {
    implicit request =>
      log.info(s"Adding an Alert!")
      request.body.asJson.map { json =>
        val alertMessage = json.as[AlertMessage]
        (ctrl.alertsActor ? Alert(alertMessage.title, alertMessage.message, alertMessage.alertClass, alertMessage.expires.getMillis, createdAt = DateTime.now.getMillis)).mapTo[Alert].map {alert =>
          Ok(s"$alert added!")
        }
      }.getOrElse {
        Future(BadRequest("{\"error\": \"Unable to parse data\"}"))
      }
  }

  def deleteAlerts(): Action[AnyContent] = Action.async {
    val futureAlerts = ctrl.alertsActor.ask(DeleteAlerts)(new Timeout(5 second))
    futureAlerts.map(s => {
      log.info(s"Removing all the alerts: $s")
      Ok(s.toString)
    }
    )
  }
}

case class AlertMessage(title: String, message: String, alertClass: String, expires: DateTime)
