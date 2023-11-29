package controllers.application

import com.google.inject.Inject
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface


class ConfigController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getAirportConfig: Action[AnyContent] = auth {
    Action { _ =>
      import upickle.default._

      Ok(write(airportConfig))
    }
  }

  def getPaxFeedSourceOrder: Action[AnyContent] = auth {
    Action { _ =>
      import upickle.default._

      Ok(write(ctrl.paxFeedSourceOrder))
    }
  }

}
