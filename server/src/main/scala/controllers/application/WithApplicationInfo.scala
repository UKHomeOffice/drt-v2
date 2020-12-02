package controllers.application

import buildinfo.BuildInfo
import controllers.Application
import drt.shared._
import play.api.mvc.{Action, AnyContent}
import upickle.default.write


trait WithApplicationInfo {
  self: Application =>

  def getApplicationConfig: Action[AnyContent] = Action { _ =>
    val rootDomain = config.get[String]("drt.domain")
    val useHttps = config.get[Boolean]("drt.use-https")
    Ok(write(ApplicationConfig(rootDomain, useHttps)))
  }
}
