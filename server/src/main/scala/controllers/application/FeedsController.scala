package controllers.application

import actors.PartitionedPortStateActor.{ GetFlightsForTerminals, PointInTimeQuery }
import actors.daily.RequestAndTerminate
import actors.persistent.arrivals._
import org.apache.pekko.NotUsed
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import com.google.inject.Inject
import drt.server.feeds.FeedPoller.AdhocCheck
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import play.api.mvc.{ Action, AnyContent, ControllerComponents }
import uk.gov.homeoffice.drt.actor.TerminalDayFeedArrivalActor
import uk.gov.homeoffice.drt.actor.commands.Commands
import uk.gov.homeoffice.drt.arrivals.{
  Arrival,
  ArrivalStatus,
  FeedArrival,
  FlightsWithSplits,
  Predictions,
  UniqueArrival
}
import uk.gov.homeoffice.drt.auth.Roles
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.feeds.{ FeedSourceStatuses, FeedStatusFailure }
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{ SDate, UtcDate }
import upickle.default.{ read, write }

import java.util.UUID
import scala.concurrent.Future
import scala.util.control.NonFatal

class FeedsController @Inject() (cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getFeedStatuses: Action[AnyContent] = auth {
    Action.async { _ =>
      ctrl.feedService.getFeedStatus.map((s: Seq[FeedSourceStatuses]) => {
        val safeStatusMessages = s
          .map(feedSourceStatuses =>
            feedSourceStatuses
              .copy(feedStatuses =
                feedSourceStatuses
                  .feedStatuses
                  .copy(feedSourceStatuses.feedStatuses.statuses.map {
                    case f: FeedStatusFailure =>
                      f.copy(message = "Unable to connect to feed.")
                    case s => s
                  })
              )
          )
        Ok(write(safeStatusMessages))
      })
    }
  }

  def checkFeed: Action[AnyContent] = authByRole(Roles.PortFeedUpload) {
    Action { request =>
      request.body.asText match {
        case None       => BadRequest
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

  def getArrival(
      number: Int,
      terminal: String,
      scheduled: MillisSinceEpoch,
      origin: String
  ): Action[AnyContent] = authByRole(ArrivalSource) {
    val term = if (ctrl.airportConfig.portCode == PortCode("EDI")) "A2" else terminal
    getAllFeedSourceArrivals(SDate.now().millisSinceEpoch, number, term, scheduled, origin)
  }

  def getArrivalAtPointInTime(
      pointInTime: MillisSinceEpoch,
      number: Int,
      terminal: String,
      scheduled: MillisSinceEpoch,
      origin: String
  ): Action[AnyContent] = authByRole(ArrivalSource) {
    getAllFeedSourceArrivals(pointInTime, number, terminal, scheduled, origin)
  }

  private def getAllFeedSourceArrivals(
      pointInTime: MillisSinceEpoch,
      number: Int,
      terminal: String,
      scheduled: MillisSinceEpoch,
      origin: String
  ): Action[AnyContent] =
    Action.async { _ =>
      getArrivalSources(pointInTime, UniqueArrival(number, terminal, scheduled, origin))
        .map(maybeFeedSources => Ok(write(maybeFeedSources)))
    }

  private def getArrivalSources(pit: Long, ua: UniqueArrival): Future[Seq[Option[FeedSourceArrival]]] =
    Future.sequence(FeedSourceArrival.orderedSourcesFor(airportConfig.feedSources).map {
      case ApiFeedSource => getApiArrivalSource(pit, ua)
      case source        =>
        FeedsController.arrivalActorPersistenceIds.get(source) match {
          case Some(id) => getFeedArrivalSource(pit, ua, id, source)
          case None     => Future.successful(None)
        }
    })

  private def getApiArrivalSource(pit: Long, ua: UniqueArrival): Future[Option[FeedSourceArrival]] = {
    val request = PointInTimeQuery(pit, GetFlightsForTerminals(ua.scheduled, ua.scheduled, Seq(ua.terminal)))
    ctrl.actorService.flightsRouterActor
      .ask(request)
      .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
      .flatMap(_.runWith(Sink.fold(FlightsWithSplits.empty)(_ ++ _._2)))
      .map(_.flights.get(ua).flatMap(fws => FeedsController.apiFeedSourceArrival(fws.apiFlight)))
      .recover {
        case NonFatal(t) =>
          log.error(s"Failed to look up API arrival for $ua: ${t.getMessage}")
          None
      }
  }

  private def getFeedArrivalSource(
                                    pit: Long,
                                    ua: UniqueArrival,
                                    id: String,
                                    source: FeedSource
                                  ): Future[Option[FeedSourceArrival]] = {
    val scheduled = SDate(ua.scheduled)
    val actor = actorSystem.actorOf(
      TerminalDayFeedArrivalActor.props(
        scheduled.getFullYear,
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
        case _ if SDate(ua.scheduled) < ctrl.feedService.legacyFeedArrivalsBeforeDate =>
          log.info(s"Using legacy feed for $source")
          val legacyActor = actorSystem.actorOf(
            ArrivalLookupActor.props(airportConfig.portCode, SDate(pit), ua, id, source),
            name = s"legacy-feed-arrival-read-$id-${UUID.randomUUID()}"
          )
          ctrl.actorService.requestAndTerminateActor
            .ask(RequestAndTerminate(legacyActor, Commands.GetState))
            .mapTo[Option[FeedSourceArrival]]
        case _ =>
          Future.successful(None)
      }
  }
}

object FeedsController {
  private[application] val arrivalActorPersistenceIds: Map[FeedSource, String] = Map(
    AclFeedSource -> AclForecastArrivalsActor.persistenceId,
    ForecastFeedSource -> PortForecastArrivalsActor.persistenceId,
    LiveBaseFeedSource -> CiriumLiveArrivalsActor.persistenceId,
    LiveFeedSource -> PortLiveArrivalsActor.persistenceId
  )

  /** API pax are derived from live API manifests and held on the flight-state arrival. Only the fields that API data
   * can vouch for are kept, so the row doesn't misattribute operational data (gate, status, times) from other feeds.
   */
  private[application] def apiFeedSourceArrival(flightStateArrival: Arrival): Option[FeedSourceArrival] =
    flightStateArrival.PassengerSources.get(ApiFeedSource).map { apiPax =>
      FeedSourceArrival(
        ApiFeedSource,
        flightStateArrival.copy(
          Operator = None,
          Status = ArrivalStatus(""),
          Estimated = None,
          Predictions = Predictions(0L, Map()),
          Actual = None,
          EstimatedChox = None,
          ActualChox = None,
          Gate = None,
          Stand = None,
          MaxPax = None,
          RunwayID = None,
          BaggageReclaimId = None,
          PreviousPort = None,
          PcpTime = None,
          FeedSources = Set(ApiFeedSource),
          CarrierScheduled = None,
          ScheduledDeparture = None,
          RedListPax = None,
          PassengerSources = Map(ApiFeedSource -> apiPax)
        )
      )
    }
}
