package controllers.application.exports

import actors.PartitionedPortStateActor.{GetFlightsForTerminalDateRange, PointInTimeQuery}
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
import drt.shared.dates.LocalDate
import drt.shared.{SDateLike, _}
import play.api.http.{HttpChunk, HttpEntity, Writeable}
import play.api.mvc._
import services.SDate
import services.exports._
import services.exports.flights.ArrivalFeedExport
import services.exports.flights.templates.{CedatFlightExportTemplate, FlightExportTemplate, FlightWithSplitsWithActualApiExportTemplate, FlightWithSplitsWithoutActualApiExportTemplate}
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ApiView, ArrivalSource, ArrivalsAndSplitsView, CedatStaff}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait WithFlightsExport {
  self: Application =>

  def csvTemplateForUser(user: LoggedInUser): FlightExportTemplate = {
    if (user.hasRole(CedatStaff))
      CedatFlightExportTemplate(Crunch.europeLondonTimeZone)
    else if (user.hasRole(ApiView))
      FlightWithSplitsWithActualApiExportTemplate(Crunch.europeLondonTimeZone)
    else
      FlightWithSplitsWithoutActualApiExportTemplate(Crunch.europeLondonTimeZone)
  }

  def exportFlightsWithSplitsForDayAtPointInTimeCSV(localDayString: String, pointInTime: MillisSinceEpoch, terminalName: String): Action[AnyContent] = {
    Action.async {
      request =>
        val user = ctrl.getLoggedInUser(config, request.headers, request.session)
        LocalDate.parse(localDayString) match {
          case Some(localDate) =>
            val startDate = SDate(localDate)
            val endDate = startDate.addDays(1).addMinutes(-1)
            val terminal = Terminal(terminalName)
            flightsRequestToCsv(pointInTime, startDate, endDate, terminal, csvTemplateForUser(user))
          case _ =>
            Future(BadRequest("Invalid date format for export day."))
        }
    }
  }

  private def flightsRequestToCsv(
                                   pointInTime: MillisSinceEpoch,
                                   startDate: SDateLike,
                                   endDate: SDateLike, terminal: Terminal,
                                   csvTemplate: FlightExportTemplate):
  Future[Result] = {
    val request = GetFlightsForTerminalDateRange(startDate.millisSinceEpoch, endDate.millisSinceEpoch, terminal)
    val pitRequest = PointInTimeQuery(pointInTime, request)
    ctrl.flightsActor.ask(pitRequest).mapTo[Source[FlightsWithSplits, NotUsed]].map { flightsStream =>
      val csvStream = StreamingFlightsExport.toCsvStreamFromTemplate(csvTemplate)(flightsStream)
      val fileName = makeFileName("flights", terminal, startDate, endDate, airportConfig.portCode)
      Try(sourceToCsvResponse(csvStream, fileName)) match {
        case Success(value) => value
        case Failure(t) =>
          log.error("Failed to get CSV export", t)
          BadRequest("Failed to get CSV export")
      }
    }
  }

  def exportFlightsWithSplitsForDateRangeCSV(startLocalDate: String,
                                             endLocalDate: String,
                                             terminalName: String): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.async {
      request =>
        val user = ctrl.getLoggedInUser(config, request.headers, request.session)
        (LocalDate.parse(startLocalDate), LocalDate.parse(endLocalDate)) match {
          case (Some(start), Some(end)) =>
            val startDate = SDate(start)
            val endDate = SDate(end).addDays(1).addMinutes(-1)
            val terminal = Terminal(terminalName)
            flightsRequestToCsv(now().millisSinceEpoch, startDate, endDate, terminal, csvTemplateForUser(user))
          case _ =>
            Future(BadRequest("Invalid date format for start or end date"))
        }
    }
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

        val fileName = s"${airportConfig.portCode}-$terminal-$feedSourceString-$periodString"

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

