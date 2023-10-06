package controllers.application

import akka.pattern.ask
import controllers.Application
import play.api.mvc.{Action, AnyContent}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs


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
