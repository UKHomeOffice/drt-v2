package controllers.application

import buildinfo.BuildInfo
import com.google.inject.Inject
import drt.shared._
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import upickle.default.write


class ApplicationInfoController @Inject()(cc: ControllerComponents,
                                          ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getApplicationVersion: Action[AnyContent] = Action { _ =>
    val shouldReload = config.getOptional[Boolean]("feature-flags.version-requires-reload").getOrElse(false)
    Ok(write(BuildVersion(BuildInfo.version, requiresReload = shouldReload)))
  }
}
