package controllers.application.exports

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminals, PointInTimeQuery}
import actors.persistent.arrivals.{AclForecastArrivalsActor, CiriumLiveArrivalsActor, PortForecastArrivalsActor, PortLiveArrivalsActor}
import akka.NotUsed
import akka.pattern.ask
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.AuthController
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse}
import drt.shared.CrunchApi.MillisSinceEpoch
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import play.api.mvc._
import services.exports.flights.ArrivalFeedExport
import services.exports.flights.templates._
import services.exports.{FlightExports, GeneralExport}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ApiView, ArrivalSource, ArrivalsAndSplitsView, SuperAdmin}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class FlightsExportController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def exportFlightsWithSplitsForDayAtPointInTimeCSV(localDateString: String,
                                                    pointInTime: MillisSinceEpoch,
                                                    terminalName: String): Action[AnyContent] =
    doExportForPointInTime(localDateString, pointInTime, Seq(Terminal(terminalName)), exportForUser)

  private def doExportForPointInTime(localDateString: String,
                                     pointInTime: MillisSinceEpoch,
                                     terminals: Seq[Terminal],
                                     exportTerminalDateRange: (LoggedInUser, PortCode, RedListUpdates) =>
                                       (LocalDate, LocalDate, Seq[Terminal]) =>
                                         FlightsExport,
                                    ): Action[AnyContent] =
    Action.async {
      request =>
        val user = ctrl.getLoggedInUser(config, request.headers, request.session)
        val maybeDate = LocalDate.parse(localDateString)
        maybeDate match {
          case Some(localDate) =>
            ctrl.applicationService.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates].flatMap { redListUpdates =>
              val export = exportTerminalDateRange(user, airportConfig.portCode, redListUpdates)(localDate, localDate, terminals)
              flightsRequestToCsv(Option(pointInTime), export)
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
    doExportForDateRange(startLocalDateString, endLocalDateString, ctrl.airportConfig.terminals.toSeq, exportForUser)
  }

  def exportFlightsWithSplitsForDateRangeApi(startLocalDateString: String,
                                             endLocalDateString: String,
                                             terminalName: String): Action[AnyContent] = Action {
    (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
      case (Some(start), Some(end)) =>
        val terminal = Terminal(terminalName)
        val getFlights = FlightExports.flightsForLocalDateRangeProvider(
          ctrl.applicationService.flightsProvider.terminalDateRangeScheduledOrPcp(terminal), ctrl.paxFeedSourceOrder)
        val getManifests = FlightExports.manifestsForLocalDateProvider(ctrl.applicationService.manifestsProvider)
        val toRows = FlightExports.dateAndFlightsToCsvRows(ctrl.airportConfig.portCode, terminal, ctrl.feedService.paxFeedSourceOrder, getManifests)
        val csvStream = GeneralExport.toCsv(start, end, getFlights, toRows)
        val fileName = makeFileName("flights", Seq(terminal), SDate(start), SDate(end), airportConfig.portCode) + ".csv"
        tryCsvResponse(csvStream, fileName)
      case _ =>
        BadRequest("Invalid date format for start or end date")
    }
  }

  private def tryCsvResponse(csvStream: Source[String, NotUsed], fileName: String): Result = {
    Try(sourceToCsvResponse(csvStream, fileName)) match {
      case Success(value) => value
      case Failure(t) =>
        log.error(s"Failed to get CSV export: ${t.getMessage}")
        BadRequest("Failed to get CSV export")
    }
  }

  private def doExportForDateRange(startLocalDateString: String,
                                   endLocalDateString: String,
                                   terminals: Seq[Terminal],
                                   exportTerminalDateRange: (LoggedInUser, PortCode, RedListUpdates)
                                     => (LocalDate, LocalDate, Seq[Terminal]) => FlightsExport,
                                  ): Action[AnyContent] = {
    Action.async {
      request =>
        val user = ctrl.getLoggedInUser(config, request.headers, request.session)
        (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
          case (Some(start), Some(end)) =>
            ctrl.applicationService.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates].flatMap { redListUpdates =>
              flightsRequestToCsv(None, exportTerminalDateRange(user, airportConfig.portCode, redListUpdates)(start, end, terminals))
            }
          case _ =>
            Future(BadRequest("Invalid date format for start or end date"))
        }
    }
  }

  private def flightsRequestToCsv(maybePointInTime: Option[MillisSinceEpoch],
                                  `export`: FlightsExport,
                                 ): Future[Result] = {
    val eventualFlightsByDate = maybePointInTime match {
      case Some(pointInTime) =>
        val requestStart = SDate(`export`.start).millisSinceEpoch
        val requestEnd = SDate(`export`.end).addDays(1).addMinutes(-1).millisSinceEpoch
        val request: FlightsRequest = GetFlightsForTerminals(requestStart, requestEnd, `export`.terminals)
        val finalRequest = PointInTimeQuery(pointInTime, request)
        ctrl.actorService.flightsRouterActor.ask(finalRequest).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
          .map(_.map { case (d, fws) => (d, fws.flights.values) })
      case None =>
        val flightsAndManifestsStream: Source[(UtcDate, Iterable[ApiFlightWithSplits]), NotUsed] =
          ctrl.flightsForPcpDateRange(`export`.start, `export`.end, `export`.terminals)
        Future.successful(flightsAndManifestsStream)
    }

    eventualFlightsByDate.map {
      flightsStream =>
        val flightsAndManifestsStream = flightsStream.mapAsync(1) { case (d, flights) =>
          val sortedFlights = flights.toSeq.sortBy(_.apiFlight.PcpTime.getOrElse(0L))
          ctrl.applicationService.manifestsProvider(d, d).map(_._2).runFold(VoyageManifests.empty)(_ ++ _).map(m => (sortedFlights, m))
        }
        val csvStream = export.csvStream(flightsAndManifestsStream)
        val fileName = makeFileName("flights", export.terminals, export.start, export.end, airportConfig.portCode) + ".csv"
        tryCsvResponse(csvStream, fileName)
    }
  }

  private val exportForUser: (LoggedInUser, PortCode, RedListUpdates) => (LocalDate, LocalDate, Seq[Terminal]) => FlightsExport =
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
        val numberOfDays = startDate.getLocalLastMidnight.daysBetweenInclusive(SDate(endPit))

        val csvDataSource = arrivalsExport.flightsDataSource(startDate, numberOfDays, terminal, fs, persistenceId)

        //        implicit val writeable: Writeable[String] = Writeable((str: String) => ByteString.fromString(str), Option("application/csv"))

        val periodString = if (numberOfDays > 1)
          s"${startDate.getLocalLastMidnight.toISODateOnly}-to-${SDate(endPit).getLocalLastMidnight.toISODateOnly}"
        else
          startDate.getLocalLastMidnight.toISODateOnly

        val fileName = s"${
          airportConfig.portCode
        }-$terminal-$feedSourceString-$periodString.csv"

        val byteStringStream = csvDataSource.collect {
          case Some(s) => s
        }

        sourceToCsvResponse(byteStringStream, fileName)

      case None =>
        NotFound(s"Unknown feed source $feedSourceString")
    })
  }
}
