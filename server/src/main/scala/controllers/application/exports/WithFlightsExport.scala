package controllers.application.exports

import actors.summaries.{FlightsSummaryActor, GetSummariesWithActualApi}
import akka.actor.ActorRef
import akka.util.ByteString
import controllers.Application
import drt.auth.{ApiView, ApiViewPortCsv, ArrivalSource, ArrivalsAndSplitsView}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, _}
import play.api.http.{HttpChunk, HttpEntity, Writeable}
import play.api.mvc._
import services.SDate
import services.exports.Exports
import services.exports.summaries.flights.{ArrivalFeedExport, TerminalFlightsSummary, TerminalFlightsSummaryLike, TerminalFlightsWithActualApiSummary}
import services.exports.summaries.{GetSummaries, TerminalSummaryLike}
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
              Exports.flightSummariesFromPortState(TerminalFlightsWithActualApiSummary.generator)(terminal, ctrl.pcpPaxFn, queryFromPortState)
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

  def exportFlightsWithSplitsAtPointInTimeCSV(pointInTime: String,
                                              terminalName: String,
                                              startHour: Int,
                                              endHour: Int): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.apply {
      implicit request => export(pointInTime, pointInTime, terminalName)
    }
  }

  def exportFlightsWithSplitsBetweenTimeStampsCSV(startMillis: String,
                                                  endMillis: String,
                                                  terminalName: String): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.apply {
      implicit request => export(startMillis, endMillis, terminalName)
    }
  }

  def exportArrivalsFromFeed(terminalString: String, startPit: MillisSinceEpoch, endPit: MillisSinceEpoch, feedSourceString: String): Action[AnyContent] = authByRole(ArrivalSource) {

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


  private def summaryProviderByRole(terminal: Terminal, flightsProvider: (SDateLike, Any) => Future[Any])
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

  private def summariesRequest(implicit request: Request[AnyContent]): Any =
    if (canAccessActualApi(request)) GetSummariesWithActualApi
    else GetSummaries

  private def export(startMillis: String, endMillis: String, terminalName: String)
                    (implicit request: Request[AnyContent]): Result = {
    val start = localLastMidnight(startMillis)
    val end = localLastMidnight(endMillis)
    val summaryForDate = summaryProviderByRole(Terminal(terminalName), queryFromPortState)
    exportToCsv(start, end, "flights", terminal(terminalName), Option(summaryActorProvider, summariesRequest), summaryForDate)
  }
}
