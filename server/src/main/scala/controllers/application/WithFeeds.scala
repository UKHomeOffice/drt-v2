package controllers.application

import controllers.Application
import drt.shared._
import play.api.mvc.{Action, AnyContent}
import upickle.default.write


trait WithFeeds {
  self: Application =>

  def getFeedStatuses: Action[AnyContent] = auth {
    Action.async { _ =>
      ctrl.getFeedStatus.map((s: Seq[FeedSourceStatuses]) => {
        val safeStatusMessages = s
          .map(feedSourceStatuses => feedSourceStatuses
            .copy(feedStatuses = feedSourceStatuses
              .feedStatuses
              .copy(feedSourceStatuses.feedStatuses.statuses.map {
          case f: FeedStatusFailure =>
            f.copy(message = "Unable to connect to feed.")
          case s => s
        })))
        Ok(write(safeStatusMessages))
      })
    }
  }
}
