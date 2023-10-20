package controllers.application

import actors.DrtSystemInterface
import com.google.inject.Inject
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import services.healthcheck.ApiHealthCheck
import spray.json.DefaultJsonProtocol._
import spray.json.enrichAny

import scala.language.postfixOps

class HealthCheckController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {
  val healthCheck: ApiHealthCheck = ApiHealthCheck(ctrl.flightsProvider.allTerminals)

  def missingLiveApiData(windowMinutes: Int): Action[AnyContent] = Action.async { _ =>
    val end = ctrl.now()
    val start = end.addMinutes(-windowMinutes)
    healthCheck.healthy(end, start).map(p => Ok(p.toJson.compactPrint))
  }
}

