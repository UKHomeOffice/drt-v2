package controllers.application

import controllers.Application
import drt.shared.api.WalkTimes
import play.api.mvc.{Action, AnyContent}
import services.PcpArrival
import uk.gov.homeoffice.drt.auth.Roles.ArrivalsAndSplitsView


trait WithWalkTimes {
  self: Application =>

  def getWalkTimes: Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action { _ =>
      import upickle.default._

      val gateWalkTimes = PcpArrival.loadWalkTimesFromCsv(ctrl.params.gateWalkTimesFilePath)
      val standWalkTimes = PcpArrival.loadWalkTimesFromCsv(ctrl.params.standWalkTimesFilePath)


      Ok(write(WalkTimes(gateWalkTimes, standWalkTimes)))
    }
  }

}
