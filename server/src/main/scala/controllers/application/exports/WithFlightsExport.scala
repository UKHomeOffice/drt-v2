package controllers.application.exports

import actors.PartitionedPortStateActor.PointInTimeQuery
import actors.persistent.arrivals.{AclForecastArrivalsActor, CirriumLiveArrivalsActor, PortForecastArrivalsActor, PortLiveArrivalsActor}
import akka.NotUsed
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.ByteString
import controllers.Application
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.dates.LocalDate
import play.api.http.{HttpChunk, HttpEntity, Writeable}
import play.api.mvc._
import services.SDate
import services.exports.flights.ArrivalFeedExport
import services.exports.flights.templates.{CedatFlightsExport, FlightsExport, FlightsWithSplitsWithActualApiExport, FlightsWithSplitsWithActualApiExportImpl, FlightsWithSplitsWithoutActualApiExport}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ApiView, ArrivalSource, ArrivalsAndSplitsView, CedatStaff}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait WithFlightsExport {
  self: Application =>

  def exportFlightsWithSplitsForDayAtPointInTimeCSV(localDateString: String, pointInTime: MillisSinceEpoch, terminalName: String): Action[AnyContent] = {
    Action.async {
      request =>
        val user = ctrl.getLoggedInUser(config, request.headers, request.session)
        LocalDate.parse(localDateString) match {
          case Some(localDate) =>
            val start = SDate(localDate)
            val end = start.addDays(1).addMinutes(-1)
            val export = exportForUser(user, airportConfig.portCode)(start, end, Terminal(terminalName))
            flightsRequestToCsv(pointInTime, export)
          case _ =>
            Future(BadRequest("Invalid date format for export day."))
        }
    }
  }

  def exportFlightsWithSplitsForDateRangeCSV(startLocalDateString: String,
                                             endLocalDateString: String,
                                             terminalName: String): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.async {
      request =>
        val user = ctrl.getLoggedInUser(config, request.headers, request.session)
        (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
          case (Some(start), Some(end)) =>
            val startDate = SDate(start)
            val endDate = SDate(end).addDays(1).addMinutes(-1)
            val export = exportForUser(user, airportConfig.portCode)(startDate, endDate, Terminal(terminalName))
            flightsRequestToCsv(now().millisSinceEpoch, export)
          case _ =>
            Future(BadRequest("Invalid date format for start or end date"))
        }
    }
  }

  private def flightsRequestToCsv(pointInTime: MillisSinceEpoch, export: FlightsExport): Future[Result] = {
    val pitRequest = PointInTimeQuery(pointInTime, export.request)
    ctrl.flightsActor.ask(pitRequest).mapTo[Source[FlightsWithSplits, NotUsed]].map {
      flightsStream =>
        val csvStream = export.csvStream(flightsStream)
        val fileName = makeFileName("flights", export.terminal, export.start, export.end, airportConfig.portCode)
        Try(sourceToCsvResponse(csvStream, fileName)) match {
          case Success(value) => value
          case Failure(t) =>
            log.error("Failed to get CSV export", t)
            BadRequest("Failed to get CSV export")
        }
    }
  }

  private def exportForUser(user: LoggedInUser, portCode: PortCode): (SDateLike, SDateLike, Terminal) => FlightsExport =
    (start, end, terminal) =>
      (user.hasRole(CedatStaff), user.hasRole(ApiView), portCode) match {
        case (true, _, _) => CedatFlightsExport(start, end, terminal)
        case (false, true, PortCode("LHR")) => FlightsWithSplitsWithActualApiExportImpl(start, end, terminal)
        case (false, false, PortCode("LHR")) => FlightsWithSplitsWithoutActualApiExport(start, end, terminal)
        case (false, true, _) => FlightsWithSplitsWithActualApiExportImpl(start, end, terminal)
        case (false, true, _) => FlightsWithSplitsWithoutActualApiExport(start, end, terminal)
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
