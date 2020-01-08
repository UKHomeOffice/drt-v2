package controllers.application

import controllers.Application
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared._
import play.api.mvc.{Action, AnyContent}
import upickle.default.write

import scala.concurrent.Future


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

  def getArrival(number: Int, terminal: String, scheduled: MillisSinceEpoch): Action[AnyContent] = auth {
    Action.async { _ =>
      val futureArrivalSources = ctrl.feedActors
        .map(feed =>
          feed
            .ask(UniqueArrival(number, terminal, scheduled))
            .map {
              case Some(fsa: FeedSourceArrival) if ctrl.isValidFeedSource(fsa.feedSource) => Option(fsa)
              case _ => None
            }
        )

      Future
        .sequence(futureArrivalSources)
        .map(arrivalSources => Ok(write(arrivalSources.filter(_.isDefined))))
    }
  }
}
