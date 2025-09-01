package controllers.application.exports

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminals, PointInTimeQuery}
import actors.persistent.arrivals.{AclForecastArrivalsActor, CiriumLiveArrivalsActor, PortForecastArrivalsActor, PortLiveArrivalsActor}
import com.google.inject.Inject
import controllers.application.AuthController
import drt.shared.CrunchApi.MillisSinceEpoch
import org.apache.pekko.NotUsed
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import play.api.mvc._
import services.exports.Exports.streamExport
import services.exports.flights.ArrivalFeedExport
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ApiView, ArrivalSource, ArrivalsAndSplitsView, SuperAdmin}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.models.{UniqueArrivalKey, VoyageManifests}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.service.QueueConfig
import uk.gov.homeoffice.drt.services.exports.{AdminExportImpl, FlightsWithSplitsExport, FlightsWithSplitsWithActualApiExportImpl, FlightsWithSplitsWithoutActualApiExportImpl}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.concurrent.Future

class FlightsExportController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def exportFlightsWithSplitsTerminalsForDayAtPointInTimeCSV(localDateString: String,
                                                    pointInTime: MillisSinceEpoch): Action[AnyContent] = {
    val terminals = ctrl.airportConfig.terminalsForDate(LocalDate.parse(localDateString).getOrElse(throw new Exception(s"Could not parse local date '$localDateString''")))
    doExportForPointInTime(localDateString, pointInTime, terminals.toSeq, exportForUser)
  }

  def exportFlightsWithSplitsForDayAtPointInTimeCSV(localDateString: String,
                                                    pointInTime: MillisSinceEpoch,
                                                    terminalName: String): Action[AnyContent] =
    doExportForPointInTime(localDateString, pointInTime, Seq(Terminal(terminalName)), exportForUser)

  private def doExportForPointInTime(localDateString: String,
                                     pointInTime: MillisSinceEpoch,
                                     terminals: Seq[Terminal],
                                     exportTerminalDateRange: (LoggedInUser, PortCode, RedListUpdates) =>
                                       (LocalDate, LocalDate, Seq[Terminal]) =>
                                         FlightsWithSplitsExport,
                                    ): Action[AnyContent] =
    Action.async {
      request =>
        val user = ctrl.getLoggedInUser(config, request.headers, request.session)
        val maybeDate = LocalDate.parse(localDateString)
        maybeDate match {
          case Some(localDate) =>
            ctrl.applicationService.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates].flatMap { redListUpdates =>
              requestToCsvStream(Option(pointInTime), exportTerminalDateRange(user, airportConfig.portCode, redListUpdates)(localDate, localDate, terminals))
                .map(streamExport(airportConfig.portCode, terminals, localDate, localDate, _, "flights"))
            }
          case _ =>
            Future(BadRequest("Invalid date format for export day."))
        }
    }

  def exportTerminalFlightsWithSplitsForDateRangeCSV(startLocalDateString: String,
                                                     endLocalDateString: String,
                                                     terminalName: String,
                                                    ): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    doExportForDateRange(startLocalDateString, endLocalDateString, Seq(Terminal(terminalName)), exportForUser)
  }

  def exportFlightsWithSplitsForDateRangeCSV(startLocalDateString: String,
                                             endLocalDateString: String,
                                            ): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    val start = LocalDate.parse(startLocalDateString).getOrElse(throw new Exception(s"Could not parse local date '$startLocalDateString''"))
    val end = LocalDate.parse(endLocalDateString).getOrElse(throw new Exception(s"Could not parse local date '$endLocalDateString''"))
    val terminals = QueueConfig.terminalsForDateRange(ctrl.airportConfig.queuesByTerminal)(start, end)
    doExportForDateRange(startLocalDateString, endLocalDateString, terminals, exportForUser)
  }

  private def doExportForDateRange(startLocalDateString: String,
                                   endLocalDateString: String,
                                   terminals: Seq[Terminal],
                                   exportTerminalDateRange: (LoggedInUser, PortCode, RedListUpdates)
                                     => (LocalDate, LocalDate, Seq[Terminal]) => FlightsWithSplitsExport,
                                  ): Action[AnyContent] =
    Action.async {
      request =>
        val user = ctrl.getLoggedInUser(config, request.headers, request.session)
        (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
          case (Some(start), Some(end)) =>
            ctrl.applicationService.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates].flatMap { redListUpdates =>
              requestToCsvStream(None, exportTerminalDateRange(user, airportConfig.portCode, redListUpdates)(start, end, terminals))
                .map(streamExport(airportConfig.portCode, terminals, start, end, _, "flights"))
            }
          case _ =>
            Future(BadRequest("Invalid date format for start or end date"))
        }
    }

  private def requestToCsvStream(maybePointInTime: Option[MillisSinceEpoch], `export`: FlightsWithSplitsExport): Future[Source[String, NotUsed]] = {
    val eventualFlightsByDate: Future[Source[(UtcDate, Iterable[ApiFlightWithSplits]), NotUsed]] = maybePointInTime match {
      case Some(pointInTime) =>
        val requestStart = SDate(`export`.start).millisSinceEpoch
        val requestEnd = SDate(`export`.end).addDays(1).addMinutes(-1).millisSinceEpoch
        val request: FlightsRequest = GetFlightsForTerminals(requestStart, requestEnd, `export`.terminals)
        val finalRequest = PointInTimeQuery(pointInTime, request)
        ctrl.actorService.flightsRouterActor.ask(finalRequest).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
          .map(_.map { case (d, fws) => (d, fws.flights.values) })
      case None =>
        val flightsAndManifestsStream = ctrl.flightsForPcpDateRange(`export`.start, `export`.end, `export`.terminals)
        Future.successful(flightsAndManifestsStream)
    }

    eventualFlightsByDate.map {
      flightsStream =>
        export.csvStream(flightsStream.mapAsync(1) { case (_, flights) =>
          val sortedFlights = flights.toSeq.sortBy(_.apiFlight.PcpTime.getOrElse(0L))
          Source(sortedFlights)
            .mapAsync(1) { fws =>
              ctrl.applicationService.manifestProvider(UniqueArrivalKey(fws.apiFlight, airportConfig.portCode))
            }
            .collect {
              case Some(vm) => vm
            }
            .runWith(Sink.seq)
            .map { manifests =>
              (sortedFlights, VoyageManifests(manifests))
            }
        })
    }
  }

  private val exportForUser: (LoggedInUser, PortCode, RedListUpdates) => (LocalDate, LocalDate, Seq[Terminal]) => FlightsWithSplitsExport =
    (user, _, _) =>
      (start, end, terminals) =>
        if (user.hasRole(SuperAdmin))
          AdminExportImpl(start, end, terminals, ctrl.feedService.paxFeedSourceOrder)
        else if (user.hasRole(ApiView))
          FlightsWithSplitsWithActualApiExportImpl(start, end, terminals, ctrl.feedService.paxFeedSourceOrder)
        else
          FlightsWithSplitsWithoutActualApiExportImpl(start, end, terminals, ctrl.feedService.paxFeedSourceOrder)

  def exportArrivalsFromFeed(terminalString: String,
                             startPit: MillisSinceEpoch,
                             endPit: MillisSinceEpoch,
                             feedSourceString: String): Action[AnyContent] = authByRole(ArrivalSource) {

    val feedSourceToPersistenceId: Map[FeedSource, String] = Map(
      LiveBaseFeedSource -> CiriumLiveArrivalsActor.persistenceId,
      LiveFeedSource -> PortLiveArrivalsActor.persistenceId,
      AclFeedSource -> AclForecastArrivalsActor.persistenceId,
      ForecastFeedSource -> PortForecastArrivalsActor.persistenceId
    )
    val terminal = Terminal(terminalString)

    Action(FeedSource(feedSourceString) match {
      case Some(fs) =>
        val persistenceId = feedSourceToPersistenceId(fs)
        val arrivalsExport = ArrivalFeedExport(ctrl.feedService.paxFeedSourceOrder)
        val startDate = SDate(startPit)
        val endDate = SDate(endPit)
        val numberOfDays = startDate.getLocalLastMidnight.daysBetweenInclusive(endDate)

        val csvDataSource = arrivalsExport.flightsDataSource(startDate, numberOfDays, terminal, fs, persistenceId)

        val stream = csvDataSource.collect { case Some(s) => s }

        streamExport(airportConfig.portCode, Seq(terminal), startDate.toLocalDate, endDate.toLocalDate, stream, s"flights-$feedSourceString")

      case None =>
        NotFound(s"Unknown feed source $feedSourceString")
    })
  }
}
