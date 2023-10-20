package controllers.application

import actors.DrtSystemInterface
import com.google.inject.Inject
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import services.healthcheck.{ApiHealthCheck, ArrivalUpdatesHealthCheck, LandingTimesHealthCheck}
import spray.json.DefaultJsonProtocol._
import spray.json.enrichAny

import scala.language.postfixOps

class HealthCheckController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {
  private val apiHealthCheck: ApiHealthCheck = ApiHealthCheck(ctrl.flightsProvider.allTerminals)
  private val landingTimesHealthCheck: LandingTimesHealthCheck = LandingTimesHealthCheck(ctrl.flightsProvider.allTerminals)
  private val arrivalUpdatesHealthCheck: ArrivalUpdatesHealthCheck = ArrivalUpdatesHealthCheck(ctrl.flightsProvider.allTerminals, 30, ctrl.now)

  def receivedLiveApiData(windowMinutes: Int, minimumToConsider: Int): Action[AnyContent] = Action.async { _ =>
    val end = ctrl.now()
    val start = end.addMinutes(-windowMinutes)
    apiHealthCheck.healthy(end, start, minimumToConsider).map(p => Ok(p.toJson.compactPrint))
  }

  def receivedLandingTimes(windowMinutes: Int, minimumToConsider: Int): Action[AnyContent] = Action.async { _ =>
    val end = ctrl.now()
    val start = end.addMinutes(-windowMinutes)
    landingTimesHealthCheck.healthy(end, start, minimumToConsider).map(p => Ok(p.toJson.compactPrint))
  }

  def receivedUpdates(windowMinutes: Int, minimumToConsider: Int): Action[AnyContent] = Action.async { _ =>
    val start = ctrl.now()
    val end = start.addMinutes(windowMinutes)
    arrivalUpdatesHealthCheck.healthy(end, start, minimumToConsider).map(p => Ok(p.toJson.compactPrint))
  }
}

