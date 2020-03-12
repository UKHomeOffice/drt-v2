package controllers.application

import controllers.Application
import play.api.mvc.{Action, AnyContent}


trait WithFeatureFlags {
  self: Application =>

  def getFeatureFlags: Action[AnyContent] = Action { _ =>
    import upickle.default._

    val frontendFeatures = Map (
      "use-api-pax-nos" -> ctrl.params.useApiPaxNos,
      "enable-toggle-display-wait-times" -> ctrl.params.enableToggleDisplayWaitTimes
    )

    Ok(write(frontendFeatures))
  }
}
