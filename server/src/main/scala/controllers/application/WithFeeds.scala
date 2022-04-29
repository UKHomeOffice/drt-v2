package controllers.application

import actors.persistent.arrivals._
import actors.persistent.staffing.GetState
import akka.actor.{ActorRef, PoisonPill}
import akka.pattern.ask
import akka.stream.scaladsl.{Sink, Source}
import controllers.Application
import drt.server.feeds.FeedPoller.AdhocCheck
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import play.api.mvc.{Action, AnyContent}
import services.SDate
import uk.gov.homeoffice.drt.arrivals.UniqueArrival
import uk.gov.homeoffice.drt.auth.Roles
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import uk.gov.homeoffice.drt.ports._
import upickle.default.{read, write}

import java.util.UUID


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

  def checkFeed: Action[AnyContent] = authByRole(Roles.PortFeedUpload) {
    Action { request =>
      request.body.asText match {
        case None => BadRequest
        case Some(text) =>
          read[FeedSource](text) match {
            case AclFeedSource =>
              log.info(s"Sending adhoc feed request to the base forecast feed actor")
              ctrl.fcstBaseActor ! AdhocCheck
            case ForecastFeedSource =>
              log.info(s"Sending adhoc feed request to the forecast feed actor")
              ctrl.fcstActor ! AdhocCheck
            case LiveBaseFeedSource =>
              log.info(s"Sending adhoc feed request to the base live feed actor")
              ctrl.liveBaseActor ! AdhocCheck
            case LiveFeedSource =>
              log.info(s"Sending adhoc feed request to the live feed actor")
              ctrl.liveActor ! AdhocCheck
            case unexpected =>
              log.info(s"Feed check not supported for $unexpected")
          }
          Ok
      }
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

    val pointInTimeActorSources: Seq[UniqueArrival => ActorRef] = arrivalActorPersistenceIds.map {
      case (id, source) =>
        (ua: UniqueArrival) =>
          system.actorOf(
            ArrivalLookupActor.props(SDate(pointInTime), ua, id, source),
            name = s"arrival-read-$id-${UUID.randomUUID()}"
          )
    }
    Action.async { _ =>
      Source(pointInTimeActorSources.toList)
        .mapAsync(1) { feedActor =>
          val actor = feedActor(UniqueArrival(number, terminal, scheduled, origin))
          actor
            .ask(GetState)
            .mapTo[Option[FeedSourceArrival]]
            .map { maybeArrival =>
              actor ! PoisonPill
              maybeArrival
            }
        }
        .log(getClass.getName)
        .runWith(Sink.seq)
        .map(arrivalSources => Ok(write(arrivalSources.filter(_.isDefined))))
    }
  }

}
