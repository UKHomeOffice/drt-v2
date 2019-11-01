package controllers.application

import buildinfo.BuildInfo
import controllers.Application
import drt.shared._
import play.api.mvc.{Action, AnyContent}
import upickle.default.write


trait WithVersion {
  self: Application =>

  def getApplicationVersion: Action[AnyContent] = Action { _ =>
    val shouldReload = config.getOptional[Boolean]("feature-flags.version-requires-reload").getOrElse(false)
    Ok(write(BuildVersion(BuildInfo.version.toString, requiresReload = shouldReload)))
  }
}
