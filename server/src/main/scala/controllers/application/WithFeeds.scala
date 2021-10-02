package controllers.application

import actors.persistent.arrivals.{AclForecastArrivalsActor, ArrivalsReadActor, CirriumLiveArrivalsActor, PortForecastArrivalsActor, PortLiveArrivalsActor}

import java.util.UUID
import akka.actor.{ActorRef, PoisonPill}
import akka.pattern.ask
import akka.stream.scaladsl.{Sink, Source}
import controllers.Application
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import play.api.mvc.{Action, AnyContent}
import services.SDate
import uk.gov.homeoffice.drt.ports.{AclFeedSource, ForecastFeedSource, LiveBaseFeedSource, LiveFeedSource}
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

  def getArrival(number: Int,
                 terminal: String,
                 scheduled: MillisSinceEpoch,
                 origin: String): Action[AnyContent] = authByRole(ArrivalSource) {
    Action.async { _ =>
      Source(ctrl.feedActorsForPort)
        .mapAsync(1) {
          case (_, feed) =>
            feed
              .ask(UniqueArrival(number, terminal, scheduled, origin))
              .map {
                case Some(fsa: FeedSourceArrival) if ctrl.isValidFeedSource(fsa.feedSource) => Option(fsa)
                case _ => None
              }
        }
        .log(getClass.getName)
        .runWith(Sink.seq)
        .map(arrivalSources => Ok(write(arrivalSources.filter(_.isDefined))))
    }
  }

  def getArrivalAtPointInTime(
                               pointInTime: MillisSinceEpoch,
                               number: Int,
                               terminal: String,
                               scheduled: MillisSinceEpoch,
                               origin: String
                             ): Action[AnyContent] = authByRole(ArrivalSource) {
    val arrivalActorPersistenceIds = Seq(
      (CirriumLiveArrivalsActor.persistenceId, LiveBaseFeedSource),
      (PortLiveArrivalsActor.persistenceId, LiveFeedSource),
      (AclForecastArrivalsActor.persistenceId, AclFeedSource),
      (PortForecastArrivalsActor.persistenceId, ForecastFeedSource)
    )

    val pointInTimeActorSources: Seq[ActorRef] = arrivalActorPersistenceIds.map {
      case (id, source) =>
        system.actorOf(
          ArrivalsReadActor.props(SDate(pointInTime), id, source),
          name = s"arrival-read-$id-${UUID.randomUUID()}"
        )
    }
    Action.async { _ =>
      Source(pointInTimeActorSources.toList)
        .mapAsync(1)((feedActor: ActorRef) => {
          feedActor
            .ask(UniqueArrival(number, terminal, scheduled, origin))
            .map {
              case Some(fsa: FeedSourceArrival) =>
                feedActor ! PoisonPill
                Option(fsa)
              case _ =>
                feedActor ! PoisonPill
                None
            }
        })
        .log(getClass.getName)
        .runWith(Sink.seq)
        .map(arrivalSources => Ok(write(arrivalSources.filter(_.isDefined))))
    }
  }

}
