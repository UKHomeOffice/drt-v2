package controllers.application

import controllers.Application
import drt.shared._
import play.api.mvc.{Action, AnyContent}
import upickle.default.write

import scala.concurrent.ExecutionContext.Implicits.global


trait WithFeeds {
  self: Application =>

  def getFeedStatuses: Action[AnyContent] = auth {
    Action.async { _ =>
      ctrl.getFeedStatus.map(s => {
        val safeStatusMessages = s.map(statusMessage => statusMessage.copy(statuses = statusMessage.statuses.map {
          case f: FeedStatusFailure =>
            f.copy(message = "Unable to connect to feed.")
          case s => s
        }))
        Ok(write(safeStatusMessages))
      })
    }
  }
}
