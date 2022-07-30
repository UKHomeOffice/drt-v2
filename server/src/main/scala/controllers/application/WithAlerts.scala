package controllers.application

import actors.persistent.DeleteAlerts
import actors.persistent.staffing.GetState
import akka.pattern._
import akka.util.Timeout
import controllers.Application
import drt.shared.Alert
import drt.shared.CrunchApi.MillisSinceEpoch
import org.joda.time.DateTime
import play.api.mvc.{Action, AnyContent}
import uk.gov.homeoffice.drt.auth.Roles.CreateAlerts
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait WithAlerts {
  self: Application =>
  
  def getAlerts(createdAfter: MillisSinceEpoch): Action[AnyContent] = Action.async { _ =>
    val eventualAlerts: Future[Seq[Alert]] = for {
      alerts <- (ctrl.alertsActor ? GetState).mapTo[Seq[Alert]]
    } yield alerts.filter(a => a.createdAt > createdAfter)

    eventualAlerts.map(alerts => Ok(write(alerts)))
  }

  def addAlert(): Action[AnyContent] = authByRole(CreateAlerts) {
    Action.async {
      implicit request =>
        log.info(s"Adding an Alert!")
        request.body.asText match {
          case Some(text) =>
            val alert: Alert = read[Alert](text)
            (ctrl.alertsActor ? Alert(alert.title, alert.message, alert.alertClass, alert.expires, createdAt = DateTime.now.getMillis)).mapTo[Alert].map { alert =>
              Ok(s"$alert added!")
            }
            Future(Accepted)
          case None =>
            Future(BadRequest)
        }
    }
  }

  def deleteAlerts(): Action[AnyContent] = authByRole(CreateAlerts) {
    Action.async {
      val futureAlerts = ctrl.alertsActor.ask(DeleteAlerts)(new Timeout(5 second))
      futureAlerts.map(s => {
        log.info(s"Removing all the alerts: $s")
        Ok(s.toString)
      })
    }
  }
}


