package controllers.application

import controllers.Application
import play.api.mvc.{Action, AnyContent}


trait WithAirportConfig {
  self: Application =>

  def getAirportConfig: Action[AnyContent] = auth {
    Action { _ =>
      import upickle.default._

      Ok(write(airportConfig))
    }
  }

}
