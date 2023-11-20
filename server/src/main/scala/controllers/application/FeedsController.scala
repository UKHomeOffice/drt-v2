package controllers.application

import actors.persistent.arrivals._
import akka.actor.{ActorRef, PoisonPill}
import akka.pattern.ask
import akka.stream.scaladsl.{Sink, Source}
import com.google.inject.Inject
import drt.server.feeds.FeedPoller.AdhocCheck
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.arrivals.UniqueArrival
import uk.gov.homeoffice.drt.auth.Roles
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.feeds.{FeedSourceStatuses, FeedStatusFailure}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.SDate
import upickle.default.{read, write}

import java.util.UUID


class FeedsController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

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
    getAllFeedSourceArrivals(SDate.now().millisSinceEpoch, number, terminal, scheduled, origin)
  }

  def getArrivalAtPointInTime(pointInTime: MillisSinceEpoch,
                              number: Int,
                              terminal: String,
                              scheduled: MillisSinceEpoch,
                              origin: String): Action[AnyContent] = authByRole(ArrivalSource) {
    getAllFeedSourceArrivals(pointInTime, number, terminal, scheduled, origin)
  }

  private def getAllFeedSourceArrivals(pointInTime: MillisSinceEpoch,
                                       number: Int,
                                       terminal: String,
                                       scheduled: MillisSinceEpoch,
                                       origin: String) =
    Action.async { _ =>
      Source(pointInTimeActorSources(pointInTime).toList)
        .mapAsync(1) { feedActor =>
          val actor = feedActor(UniqueArrival(number, terminal, scheduled, origin))
          actor
            .ask(GetState)
            .mapTo[Option[FeedSourceArrival]]
            .map { result =>
              actor ! PoisonPill
              result
            }
            .map {
              case Some(feedSourceArrival) if ctrl.isValidFeedSource(feedSourceArrival.feedSource) =>
                Option(feedSourceArrival)
              case _ => None
            }
        }
        .log(getClass.getName)
        .runWith(Sink.seq)
        .map(arrivalSources => Ok(write(arrivalSources)))
    }

  private val arrivalActorPersistenceIds = Seq(
    (CirriumLiveArrivalsActor.persistenceId, LiveBaseFeedSource),
    (PortLiveArrivalsActor.persistenceId, LiveFeedSource),
    (AclForecastArrivalsActor.persistenceId, AclFeedSource),
    (PortForecastArrivalsActor.persistenceId, ForecastFeedSource)
  )

  private def pointInTimeActorSources(pit: MillisSinceEpoch): Seq[UniqueArrival => ActorRef] = {
    arrivalActorPersistenceIds.map {
      case (id, source) =>
        (ua: UniqueArrival) =>
          actorSystem.actorOf(
            ArrivalLookupActor.props(airportConfig.portCode, SDate(pit), ua, id, source),
            name = s"arrival-read-$id-${UUID.randomUUID()}"
          )
    }
  }

}
