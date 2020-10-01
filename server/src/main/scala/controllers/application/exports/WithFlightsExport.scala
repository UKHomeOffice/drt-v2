package controllers.application.exports

import actors.PartitionedPortStateActor.{DateRangeLike, GetFlightsForTerminalDateRange, PointInTimeQuery}
import actors.summaries.{FlightsSummaryActor, GetSummariesWithActualApi}
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.ByteString
import controllers.Application
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse}
import drt.auth.{ApiView, ApiViewPortCsv, ArrivalSource, ArrivalsAndSplitsView}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, _}
import play.api.http.{HttpChunk, HttpEntity, Writeable}
import play.api.mvc._
import services.SDate
import services.exports.summaries.flights.{ArrivalFeedExport, TerminalFlightsSummary, TerminalFlightsWithActualApiSummary}
import services.exports.summaries.{GetSummaries, TerminalSummaryLike}
import services.exports.{Exports, StreamingFlightsExport}
import services.graphstages.Crunch.europeLondonTimeZone

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait WithFlightsExport extends ExportToCsv {
  self: Application =>

  def exportApi(day: Int,
                month: Int,
                year: Int,
                terminalName: String): Action[AnyContent] = authByRole(ApiViewPortCsv) {
    Action.apply {
      implicit request =>
        val terminal = Terminal(terminalName)
        Try(SDate(year, month, day, 0, 0, europeLondonTimeZone)) match {
          case Success(start) =>
            val summaryFromPortState: (SDateLike, SDateLike) => Future[TerminalSummaryLike] =
              Exports.flightSummariesFromPortState(TerminalFlightsWithActualApiSummary.generator)(terminal, ctrl.pcpPaxFn, queryFromPortStateFn(None))
            exportToCsv(
              start = start,
              end = start,
              description = "flights",
              terminal = terminal,
              maybeSummaryActorAndRequestProvider = Option(summaryActorProvider, GetSummariesWithActualApi),
              generateNewSummary = summaryFromPortState
            )
          case Failure(t) =>
            log.error(f"Bad date date $year%02d/$month%02d/$day%02d", t)
            BadRequest(f"Bad date date $year%02d/$month%02d/$day%02d")
        }
    }
  }

  def exportFlightsWithSplitsForDayAtPointInTimeCSV(localDayString: String, pointInTime: MillisSinceEpoch, terminalName: String): Action[AnyContent] = {
    Action.async {
      _ =>
        LocalDate.parse(localDayString) match {
          case Some(localDate) =>
            val startDate = SDate(localDate)
            val endDate = startDate.addDays(1).addMinutes(-1)
            val terminal = Terminal(terminalName)
            flightsRequestToCsv(pointInTime, startDate, endDate, terminal)
          case _ =>
            Future(BadRequest("Invalid date format for export day."))
        }
    }
  }

  private def flightsRequestToCsv(pointInTime: MillisSinceEpoch,
                                  startDate: SDateLike,
                                  endDate: SDateLike,
                                  terminal: Terminal): Future[Result] = {
    val request = GetFlightsForTerminalDateRange(startDate.millisSinceEpoch, endDate.millisSinceEpoch, terminal)
    val pitRequest = PointInTimeQuery(pointInTime, request)
    ctrl.flightsActor.ask(pitRequest).mapTo[Source[FlightsWithSplits, NotUsed]].map { flightsStream =>
      log.info(s"******* What's this?: $flightsStream")
      val csvStream = StreamingFlightsExport(ctrl.pcpPaxFn).toCsvStream(flightsStream)
      log.info(s"***** csvSream $csvStream")
      csvStream.map(s => s"********* csv line: $s")
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
      _ =>
        (LocalDate.parse(startLocalDate), LocalDate.parse(endLocalDate)) match {
          case (Some(start), Some(end)) =>
            val startDate = SDate(start)
            val endDate = SDate(end).addDays(1).addMinutes(-1)
            val terminal = Terminal(terminalName)
            flightsRequestToCsv(now().millisSinceEpoch, startDate, endDate, terminal)
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
      LiveBaseFeedSource -> "actors.LiveBaseArrivalsActor-live-base",
      LiveFeedSource -> "actors.LiveArrivalsActor-live",
      AclFeedSource -> "actors.ForecastBaseArrivalsActor-forecast-base",
      ForecastFeedSource -> "actors.ForecastPortArrivalsActor-forecast-port"
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
  
  private def summaryProviderByRole(terminal: Terminal, flightsProvider: DateRangeLike => Future[Any])
                                   (implicit request: Request[AnyContent]): (SDateLike, SDateLike) => Future[TerminalSummaryLike] = {
    val flightSummariesFromPortState =
      if (canAccessActualApi(request))
        Exports.flightSummariesFromPortState(TerminalFlightsWithActualApiSummary.generator) _
      else
        Exports.flightSummariesFromPortState(TerminalFlightsSummary.generator) _

    flightSummariesFromPortState(terminal, ctrl.pcpPaxFn, flightsProvider)
  }

  private def canAccessActualApi(request: Request[AnyContent]) = {
    ctrl.getRoles(config, request.headers, request.session).contains(ApiView)
  }

  private val summaryActorProvider: (SDateLike, Terminal) => ActorRef = (date: SDateLike, terminal: Terminal) => {
    system.actorOf(FlightsSummaryActor.props(date, terminal, ctrl.pcpPaxFn, now))
  }
}

