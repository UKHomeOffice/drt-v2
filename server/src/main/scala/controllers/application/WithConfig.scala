package controllers.application

import controllers.Application
import play.api.mvc.{Action, AnyContent}


trait WithConfig {
  self: Application =>

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
