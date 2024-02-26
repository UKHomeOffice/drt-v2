package controllers.application

import com.google.inject.Inject
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import providers.MinutesProvider
import services.healthcheck.{ApiHealthCheck, ArrivalUpdatesHealthCheck, DeskUpdatesHealthCheck, LandingTimesHealthCheck}
import spray.json.DefaultJsonProtocol._
import spray.json.enrichAny
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface


class HealthCheckController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {
  private val apiHealthCheck: ApiHealthCheck = ApiHealthCheck(ctrl.applicationService.flightsProvider.allTerminals)
  private val landingTimesHealthCheck: LandingTimesHealthCheck = LandingTimesHealthCheck(ctrl.applicationService.flightsProvider.allTerminals)
  private val arrivalUpdatesHealthCheck: ArrivalUpdatesHealthCheck = ArrivalUpdatesHealthCheck(ctrl.applicationService.flightsProvider.allTerminals, 30, ctrl.now)
  private val deskUpdatesHealthCheck: DeskUpdatesHealthCheck = DeskUpdatesHealthCheck(
    ctrl.now,
    ctrl.applicationService.flightsProvider.allTerminals,
    MinutesProvider.allTerminals(ctrl.actorService.queuesRouterActor)
  )

  def receivedLiveApiData(windowMinutes: Int, minimumToConsider: Int): Action[AnyContent] = Action.async { _ =>
    val end = ctrl.now()
    val start = end.addMinutes(-windowMinutes)
    apiHealthCheck.healthy(start, end, minimumToConsider).map(p => Ok(p.toJson.compactPrint))
  }

  def receivedLandingTimes(windowMinutes: Int, minimumToConsider: Int): Action[AnyContent] = Action.async { _ =>
    val end = ctrl.now()
    val start = end.addMinutes(-windowMinutes)
    landingTimesHealthCheck.healthy(start, end, minimumToConsider).map(p => Ok(p.toJson.compactPrint))
  }

  def receivedUpdates(windowMinutes: Int, minimumToConsider: Int): Action[AnyContent] = Action.async { _ =>
    val start = ctrl.now()
    val end = start.addMinutes(windowMinutes)
    arrivalUpdatesHealthCheck.healthy(start, end, minimumToConsider).map(p => Ok(p.toJson.compactPrint))
  }

  def deskUpdates(): Action[AnyContent] = Action.async { _ =>
    deskUpdatesHealthCheck.healthy().map(p => Ok(p.toJson.compactPrint))
  }}

