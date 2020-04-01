package controllers.application.exports

import actors.summaries.{FlightsSummaryActor, GetSummariesWithActualApi}
import akka.actor.ActorRef
import akka.util.ByteString
import controllers.Application
import drt.auth.{ApiView, ApiViewPortCsv, ArrivalSource, ArrivalsAndSplitsView}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared._
import play.api.http.{HttpChunk, HttpEntity, Writeable}
import play.api.mvc._
import services.SDate
import services.exports.Exports
import services.exports.summaries.flights.ArrivalFeedExport
import services.exports.summaries.{GetSummaries, TerminalSummaryLike}
import services.graphstages.Crunch.europeLondonTimeZone

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
            val summaryFromPortState = Exports.flightSummariesWithActualApiFromPortState(terminal)
            exportToCsv(start, start, "flights", terminal, Option(summaryActorProvider, GetSummariesWithActualApi), summaryFromPortState)
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
        val numberOfDays = startDate.daysBetweenInclusive(SDate(endPit))
        val csvDataSource = arrivalsExport.flightsDataSource(startDate, numberOfDays, terminal, fs, persistenceId)

        implicit val writeable: Writeable[String] = Writeable((str: String) => ByteString.fromString(str), Option("application/csv"))
        val fileName = s"${airportConfig.portCode}-$terminal-$feedSourceString-${startDate.toISODateOnly}-to-${SDate(endPit).toISODateOnly}"
        Result(
          header = ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
          body = HttpEntity.Chunked(csvDataSource.collect {
            case Some(s) => s
          }.map(c => HttpChunk.Chunk(writeable.transform(c))), writeable.contentType))

      case None =>
        NotFound(s"Unknown feed source $feedSourceString")
    })
  }


  private def summaryProviderByRole(terminal: Terminal)
                                   (implicit request: Request[AnyContent]): (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike] =
    if (canAccessActualApi(request)) Exports.flightSummariesWithActualApiFromPortState(terminal)
    else Exports.flightSummariesFromPortState(terminal)

  private def canAccessActualApi(request: Request[AnyContent]) = {
    ctrl.getRoles(config, request.headers, request.session).contains(ApiView)
  }

  private val summaryActorProvider: (SDateLike, Terminal) => ActorRef = (date: SDateLike, terminal: Terminal) => {
    system.actorOf(FlightsSummaryActor.props(date, terminal, now))
  }

  private def summariesRequest(implicit request: Request[AnyContent]): Any =
    if (canAccessActualApi(request)) GetSummariesWithActualApi
    else GetSummaries

  private def export(startMillis: String, endMillis: String, terminalName: String)
                    (implicit request: Request[AnyContent]): Result = {
    val start = localLastMidnight(startMillis)
    val end = localLastMidnight(endMillis)
    val summaryFromPortState = summaryProviderByRole(Terminal(terminalName))
    exportToCsv(start, end, "flights", terminal(terminalName), Option(summaryActorProvider, summariesRequest), summaryFromPortState)
  }
}
