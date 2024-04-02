package controllers.application

import actors.daily.RequestAndTerminate
import actors.persistent.arrivals._
import akka.pattern.ask
import com.google.inject.Inject
import drt.server.feeds.FeedPoller.AdhocCheck
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.actor.TerminalDayFeedArrivalActor
import uk.gov.homeoffice.drt.actor.commands.Commands
import uk.gov.homeoffice.drt.arrivals.{FeedArrival, UniqueArrival}
import uk.gov.homeoffice.drt.auth.Roles
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.feeds.{FeedSourceStatuses, FeedStatusFailure}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.SDate
import upickle.default.{read, write}

import java.util.UUID
import scala.concurrent.Future


class FeedsController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getFeedStatuses: Action[AnyContent] = auth {
    Action.async { _ =>
      ctrl.feedService.getFeedStatus.map((s: Seq[FeedSourceStatuses]) => {
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
              ctrl.feedService.fcstBaseFeedPollingActor ! AdhocCheck
            case ForecastFeedSource =>
              log.info(s"Sending adhoc feed request to the forecast feed actor")
              ctrl.feedService.fcstFeedPollingActor ! AdhocCheck
            case LiveBaseFeedSource =>
              log.info(s"Sending adhoc feed request to the base live feed actor")
              ctrl.feedService.liveBaseFeedPollingActor ! AdhocCheck
            case LiveFeedSource =>
              log.info(s"Sending adhoc feed request to the live feed actor")
              ctrl.feedService.liveFeedPollingActor ! AdhocCheck
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
      getArrivalSources(pointInTime, UniqueArrival(number, terminal, scheduled, origin))
        .map(maybeFeedSources => Ok(write(maybeFeedSources)))
    }

  private val arrivalActorPersistenceIds = Seq(
    (CiriumLiveArrivalsActor.persistenceId, LiveBaseFeedSource),
    (PortLiveArrivalsActor.persistenceId, LiveFeedSource),
    (AclForecastArrivalsActor.persistenceId, AclFeedSource),
    (PortForecastArrivalsActor.persistenceId, ForecastFeedSource)
  )

  private val legacyFeedArrivalsBeforeDate = SDate("2024-04-02T00:00")

  private def getArrivalSources(pit: Long, ua: UniqueArrival): Future[Seq[Option[FeedSourceArrival]]] =
    Future.sequence(arrivalActorPersistenceIds.map {
      case (id, source) =>
        val scheduled = SDate(ua.scheduled)
        val actor = actorSystem.actorOf(
          TerminalDayFeedArrivalActor.props(scheduled.getFullYear,
            scheduled.getMonth,
            scheduled.getDate,
            ua.terminal,
            source,
            Option(pit),
            () => ctrl.now().millisSinceEpoch,
            250
          ),
          name = s"feed-arrival-read-$id-${UUID.randomUUID()}"
        )

        ctrl.actorService.requestAndTerminateActor
          .ask(RequestAndTerminate(actor, TerminalDayFeedArrivalActor.GetState))
          .mapTo[Map[UniqueArrival, FeedArrival]]
          .flatMap {
            case m if m.nonEmpty =>
              val maybeSourceArrival = m.get(ua).map(fa => FeedSourceArrival(source, fa.toArrival(source)))
              Future.successful(maybeSourceArrival)
            case _ if SDate(ua.scheduled) < legacyFeedArrivalsBeforeDate =>
              log.info(s"Using legacy feed for $source")
              val legacyActor = actorSystem.actorOf(
                ArrivalLookupActor.props(airportConfig.portCode, SDate(pit), ua, id, source),
                name = s"legacy-feed-arrival-read-$id-${UUID.randomUUID()}"
              )
              ctrl.actorService.requestAndTerminateActor
                .ask(RequestAndTerminate(legacyActor, Commands.GetState))
                .mapTo[Option[FeedSourceArrival]]
            case notFound =>
              Future.successful(None)
          }
    })
}
