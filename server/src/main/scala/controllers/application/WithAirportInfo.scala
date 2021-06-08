package controllers.application

import controllers.Application
import uk.gov.homeoffice.drt.auth.Roles.ArrivalsAndSplitsView
import drt.shared._
import play.api.mvc.{Action, AnyContent}
import services.AirportToCountry


trait WithAirportInfo {
  self: Application =>

  def getAirportInfo: Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action { request =>
      import upickle.default._

      val res: Map[PortCode, AirportInfo] = request.queryString.get("portCode")
        .flatMap(_.headOption)
        .map(codes => codes
          .split(",")
          .map(code => (PortCode(code), AirportToCountry.airportInfoByIataPortCode.get(code)))
          .collect {
            case (code, Some(info)) => (code, info)
          }
        ) match {
        case Some(airportInfoTuples) => airportInfoTuples.toMap
        case None => Map()
      }

      Ok(write(res))
    }
  }

}
