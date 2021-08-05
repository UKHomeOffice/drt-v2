package controllers.application

import controllers.Application
import uk.gov.homeoffice.drt.auth.Roles.ArrivalsAndSplitsView
import drt.shared._
import play.api.mvc.{Action, AnyContent}
import services.{AirportToCountry, SDate}


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

  def getRedListPorts(dateString: String): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action { _ =>
      import upickle.default._

      val redListPorts = AirportToCountry.airportInfoByIataPortCode.values.collect {
        case AirportInfo(_, _, country, portCode) if RedList.countryCodesByName(SDate(dateString).millisSinceEpoch).contains(country) => PortCode(portCode)
      }

      Ok(write(redListPorts))
    }
  }
}
