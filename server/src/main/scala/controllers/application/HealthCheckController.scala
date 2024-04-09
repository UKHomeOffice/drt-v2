package controllers.application

import com.google.inject.Inject
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import providers.MinutesProvider
import services.healthcheck.{ApiHealthCheck, ArrivalUpdatesHealthCheck, DeskUpdatesHealthCheck, LandingTimesHealthCheck}
import spray.json.DefaultJsonProtocol._
import spray.json.enrichAny
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.time.SDate


class HealthCheckController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {
  private val apiHealthCheck: ApiHealthCheck = ApiHealthCheck(ctrl.applicationService.flightsProvider.allTerminals)
  private val landingTimesHealthCheck: LandingTimesHealthCheck = LandingTimesHealthCheck(ctrl.applicationService.flightsProvider.allTerminals)
  private val arrivalUpdatesHealthCheck: Int => ArrivalUpdatesHealthCheck = ArrivalUpdatesHealthCheck(ctrl.applicationService.flightsProvider.allTerminals, ctrl.now)
  private val deskUpdatesHealthCheck: DeskUpdatesHealthCheck = DeskUpdatesHealthCheck(
    ctrl.now,
    ctrl.applicationService.flightsProvider.allTerminals,
    MinutesProvider.allTerminals(ctrl.actorService.queuesRouterActor)
  )

  def receivedLiveApiData(from: String, to: String, minimumToConsider: Int): Action[AnyContent] = Action.async { _ =>
    val start = SDate(from)
    val end = SDate(to)
    apiHealthCheck.healthy(start, end, minimumToConsider).map(p => Ok(p.toJson.compactPrint))
  }

  def receivedLandingTimes(from: String, to: String, minimumToConsider: Int): Action[AnyContent] = Action.async { _ =>
    val start = SDate(from)
    val end = SDate(to)
    landingTimesHealthCheck.healthy(start, end, minimumToConsider).map(p => Ok(p.toJson.compactPrint))
  }

  def receivedUpdates(from: String, to: String, minimumToConsider: Int, lastUpdatedMinutes: Int): Action[AnyContent] = Action.async { _ =>
    val start = SDate(from)
    val end = SDate(to)
    arrivalUpdatesHealthCheck(lastUpdatedMinutes).healthy(start, end, minimumToConsider).map(p => Ok(p.toJson.compactPrint))
  }

  def deskUpdates(): Action[AnyContent] = Action.async { _ =>
    deskUpdatesHealthCheck.healthy().map(p => Ok(p.toJson.compactPrint))
  }
}

