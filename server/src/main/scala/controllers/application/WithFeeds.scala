package controllers.application

import java.util.UUID

import actors.pointInTime.ArrivalsReadActor
import akka.actor.{ActorRef, PoisonPill}
import akka.pattern.ask
import controllers.Application
import drt.auth.ArrivalSource
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import play.api.mvc.{Action, AnyContent}
import services.SDate
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

  def getArrival(number: Int, terminal: String, scheduled: MillisSinceEpoch): Action[AnyContent] = authByRole(ArrivalSource) {
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

  def getArrivalAtPointInTime(
                               pointInTime: MillisSinceEpoch,
                               number: Int, terminal: String,
                               scheduled: MillisSinceEpoch
                             ): Action[AnyContent] = authByRole(ArrivalSource) {
    val arrivalActorPersistenceIds = Seq(
      ("actors.LiveBaseArrivalsActor-live-base", LiveBaseFeedSource),
      ("actors.LiveArrivalsActor-live", LiveFeedSource),
      ("actors.ForecastBaseArrivalsActor-forecast-base", AclFeedSource),
      ("actors.ForecastPortArrivalsActor-forecast-port", ForecastFeedSource)
    )

    val pointInTimeActorSources: Seq[ActorRef] = arrivalActorPersistenceIds.map {
      case (id, source) =>
        system.actorOf(
          ArrivalsReadActor.props(SDate(pointInTime), id, source),
          name = s"arrival-read-$id-${UUID.randomUUID()}"
        )
    }
    Action.async { _ =>

      val futureArrivalSources = pointInTimeActorSources.map((feedActor: ActorRef) => {
        feedActor
          .ask(UniqueArrival(number, terminal, scheduled))
          .map {
            case Some(fsa: FeedSourceArrival) =>
              feedActor ! PoisonPill
              Option(fsa)
            case _ =>
              feedActor ! PoisonPill
              None
          }
      })
      Future
        .sequence(futureArrivalSources)
        .map(arrivalSources => Ok(write(arrivalSources.filter(_.isDefined))))
    }
  }

}
