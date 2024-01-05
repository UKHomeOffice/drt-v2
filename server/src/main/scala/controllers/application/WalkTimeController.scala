package controllers.application

import com.google.inject.Inject
import drt.shared.api.{WalkTime, WalkTimes}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.actor.WalkTimeProvider
import uk.gov.homeoffice.drt.auth.Roles.ArrivalsAndSplitsView
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface

trait WalkTimeLike {
  private def walkTimes(csvPath: String): Iterable[WalkTime] = WalkTimeProvider.walkTimes(csvPath).map {
    case ((terminal, gateOrStand), walkTimeSeconds) => WalkTime(gateOrStand, terminal, walkTimeSeconds * 1000)
  }

  protected def walkTimes(walkTimesFilePath: Option[String]): Iterable[WalkTime] = walkTimesFilePath.map(walkTimes).getOrElse(Iterable())

}

class WalkTimeController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) with WalkTimeLike {

  def getWalkTimes: Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action { _ =>
      import upickle.default._

      val gates = walkTimes(ctrl.params.gateWalkTimesFilePath)
      val stands = walkTimes(ctrl.params.standWalkTimesFilePath)

      Ok(write(WalkTimes(gates, stands)))
    }
  }


}
