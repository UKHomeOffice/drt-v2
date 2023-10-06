package controllers.application

import actors.DrtSystemInterface
import com.google.inject.Inject
import drt.shared.api.{WalkTime, WalkTimes}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.actor.WalkTimeProvider
import uk.gov.homeoffice.drt.auth.Roles.ArrivalsAndSplitsView

class WalkTimeController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getWalkTimes: Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action { _ =>
      import upickle.default._

      val gates = ctrl.params.gateWalkTimesFilePath.map(walkTimes).getOrElse(Iterable())
      val stands = ctrl.params.standWalkTimesFilePath.map(walkTimes).getOrElse(Iterable())

      Ok(write(WalkTimes(gates, stands)))
    }
  }

  private def walkTimes(csvPath: String): Iterable[WalkTime] = WalkTimeProvider.walkTimes(csvPath).map {
    case ((terminal, gateOrStand), walkTimeSeconds) => WalkTime(gateOrStand, terminal, walkTimeSeconds * 1000)
  }
}
