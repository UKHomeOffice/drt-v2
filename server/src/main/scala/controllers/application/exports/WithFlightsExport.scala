package controllers.application.exports

import actors.PartitionedPortStateActor.{GetStateForDateRange, PointInTimeQuery}
import actors.persistent.arrivals.{AclForecastArrivalsActor, CirriumLiveArrivalsActor, PortForecastArrivalsActor, PortLiveArrivalsActor}
import actors.persistent.staffing.GetState
import akka.NotUsed
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.ByteString
import controllers.Application
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import play.api.http.{HttpChunk, HttpEntity, Writeable}
import play.api.mvc._
import services.SDate
import services.exports.flights.ArrivalFeedExport
import services.exports.flights.templates._
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ApiView, ArrivalSource, ArrivalsAndSplitsView, CedatStaff}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike, UtcDate}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait WithFlightsExport {
  self: Application =>

  def exportFlightsWithSplitsForDayAtPointInTimeCSV(localDateString: String,
                                                    pointInTime: MillisSinceEpoch,
                                                    terminalName: String): Action[AnyContent] =
    doExportForPointInTime(localDateString, pointInTime, terminalName, exportForUser)

  def exportFlightsWithSplitsForDayAtPointInTimeCSVWithRedListDiversions(localDateString: String,
                                                                         pointInTime: MillisSinceEpoch,
                                                                         terminalName: String): Action[AnyContent] =
    doExportForPointInTime(localDateString, pointInTime, terminalName, redListDiversionsExportForUser)

  private def doExportForPointInTime(localDateString: String,
                                     pointInTime: MillisSinceEpoch,
                                     terminalName: String,
                                     export: (LoggedInUser, PortCode, RedListUpdates) => (SDateLike, SDateLike, Terminal) => FlightsExport): Action[AnyContent] =
    Action.async {
      request =>
        val user = ctrl.getLoggedInUser(config, request.headers, request.session)
        val maybeDate = LocalDate.parse(localDateString)
        maybeDate match {
          case Some(localDate) =>
            val start = SDate(localDate)
            val end = start.addDays(1).addMinutes(-1)
            ctrl.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates].flatMap { redListUpdates =>
              flightsRequestToCsv(pointInTime, export(user, airportConfig.portCode, redListUpdates)(start, end, Terminal(terminalName)))
            }
          case _ =>
            Future(BadRequest("Invalid date format for export day."))
        }
    }

  def exportFlightsWithSplitsForDateRangeCSV(startLocalDateString: String,
                                             endLocalDateString: String,
                                             terminalName: String): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    doExportForDateRange(startLocalDateString, endLocalDateString, terminalName, exportForUser)
  }

  def exportFlightsWithSplitsForDateRangeCSVWithRedListDiversions(startLocalDateString: String,
                                                                  endLocalDateString: String,
                                                                  terminalName: String): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    doExportForDateRange(startLocalDateString, endLocalDateString, terminalName, redListDiversionsExportForUser)
  }

  private def doExportForDateRange(startLocalDateString: String,
                                   endLocalDateString: String,
                                   terminalName: String,
                                   export: (LoggedInUser, PortCode, RedListUpdates) => (SDateLike, SDateLike, Terminal) => FlightsExport): Action[AnyContent] = {
    Action.async {
      request =>
        val user = ctrl.getLoggedInUser(config, request.headers, request.session)
        (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
          case (Some(start), Some(end)) =>
            val startDate = SDate(start)
            val endDate = SDate(end).addDays(1).addMinutes(-1)
            ctrl.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates].flatMap { redListUpdates =>
              flightsRequestToCsv(now().millisSinceEpoch, export(user, airportConfig.portCode, redListUpdates)(startDate, endDate, Terminal(terminalName)))
            }
          case _ =>
            Future(BadRequest("Invalid date format for start or end date"))
        }
    }
  }

  private def flightsRequestToCsv(pointInTime: MillisSinceEpoch, export: FlightsExport): Future[Result] = {
    val pitRequest = PointInTimeQuery(pointInTime, export.request)
    ctrl.flightsActor.ask(pitRequest).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]].map {
      flightsStream =>
        val flightsAndManifestsStream = flightsStream.mapAsync(1) { case (d, fws) =>
          ctrl.manifestsRouterActor
            .ask(GetStateForDateRange(SDate(d).millisSinceEpoch, SDate(d).addDays(1).addMinutes(-1).millisSinceEpoch))
            .mapTo[Source[VoyageManifests, NotUsed]]
            .flatMap(_.runFold(VoyageManifests.empty)(_ ++ _))
            .map(manifests => (fws, manifests))
        }
        val csvStream = export.csvStream(flightsAndManifestsStream)
        val fileName = makeFileName("flights", export.terminal, export.start, export.end, airportConfig.portCode)
        Try(sourceToCsvResponse(csvStream, fileName)) match {
          case Success(value) => value
          case Failure(t) =>
            log.error("Failed to get CSV export", t)
            BadRequest("Failed to get CSV export")
        }
    }
  }

  private val exportForUser: (LoggedInUser, PortCode, RedListUpdates) => (SDateLike, SDateLike, Terminal) => FlightsExport =
    (user, portCode, _) =>
      (start, end, terminal) =>
        (user.hasRole(CedatStaff), user.hasRole(ApiView), portCode) match {
          case (true, _, _) => CedatFlightsExport(start, end, terminal)
          case (false, true, _) => FlightsWithSplitsWithActualApiExportImpl(start, end, terminal)
          case (false, false, _) => FlightsWithSplitsWithoutActualApiExportImpl(start, end, terminal)
        }

  private val redListDiversionsExportForUser: (LoggedInUser, PortCode, RedListUpdates) => (SDateLike, SDateLike, Terminal) => FlightsExport =
    (user, portCode, redListUpdates) =>
      (start, end, terminal) =>
        (user.hasRole(CedatStaff), user.hasRole(ApiView), portCode) match {
          case (true, _, _) => CedatFlightsExport(start, end, terminal)
          case (false, true, PortCode("LHR")) => LHRFlightsWithSplitsWithActualApiExportWithRedListDiversions(start, end, terminal, redListUpdates)
          case (false, false, PortCode("LHR")) => LHRFlightsWithSplitsWithoutActualApiExportWithRedListDiversions(start, end, terminal, redListUpdates)
          case (false, true, PortCode("BHX")) => BhxFlightsWithSplitsWithActualApiExportWithCombinedTerminals(start, end, terminal)
          case (false, false, PortCode("BHX")) => BhxFlightsWithSplitsWithoutActualApiExportWithCombinedTerminals(start, end, terminal)
          case (false, true, _) => FlightsWithSplitsWithActualApiExportImpl(start, end, terminal)
          case (false, false, _) => FlightsWithSplitsWithoutActualApiExportImpl(start, end, terminal)
        }

  def exportArrivalsFromFeed(terminalString: String,
                             startPit: MillisSinceEpoch,
                             endPit: MillisSinceEpoch,
                             feedSourceString: String): Action[AnyContent] = authByRole(ArrivalSource) {

    val feedSourceToPersistenceId: Map[FeedSource, String] = Map(
      LiveBaseFeedSource -> CirriumLiveArrivalsActor.persistenceId,
      LiveFeedSource -> PortLiveArrivalsActor.persistenceId,
      AclFeedSource -> AclForecastArrivalsActor.persistenceId,
      ForecastFeedSource -> PortForecastArrivalsActor.persistenceId
    )
    val terminal = Terminal(terminalString)

    Action(FeedSource(feedSourceString) match {
      case Some(fs) =>
        val persistenceId = feedSourceToPersistenceId(fs)
        val arrivalsExport = ArrivalFeedExport()
        val startDate = SDate(startPit)
        val numberOfDays = startDate.getLocalLastMidnight.daysBetweenInclusive(SDate(endPit))

        val csvDataSource = arrivalsExport.flightsDataSource(startDate, numberOfDays, terminal, fs, persistenceId)

        implicit val writeable: Writeable[String] = Writeable((str: String) => ByteString.fromString(str), Option("application/csv"))

        val periodString = if (numberOfDays > 1)
          s"${startDate.getLocalLastMidnight.toISODateOnly}-to-${SDate(endPit).getLocalLastMidnight.toISODateOnly}"
        else
          startDate.getLocalLastMidnight.toISODateOnly

        val fileName = s"${
          airportConfig.portCode
        }-$terminal-$feedSourceString-$periodString"

        Result(
          header = ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
          body = HttpEntity.Chunked(csvDataSource.collect {
            case Some(s) => s
          }.map(c => HttpChunk.Chunk(writeable.transform(c))), writeable.contentType))

      case None =>
        NotFound(s"Unknown feed source $feedSourceString")
    })
  }
}
