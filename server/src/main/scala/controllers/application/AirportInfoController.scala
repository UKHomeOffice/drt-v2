package controllers.application

import com.google.inject.Inject
import drt.shared._
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import services.AirportToCountry
import uk.gov.homeoffice.drt.auth.Roles.ArrivalsAndSplitsView
import uk.gov.homeoffice.drt.ports.PortCode


class AirportInfoController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

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
