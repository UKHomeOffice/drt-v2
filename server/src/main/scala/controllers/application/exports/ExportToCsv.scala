package controllers.application.exports

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import controllers.Application
import drt.shared.Terminals.Terminal
import drt.shared.{PortCode, PortState, SDateLike}
import play.api.http.{HttpChunk, HttpEntity, Writeable}
import play.api.mvc.{ResponseHeader, Result}
import services.SDate
import services.exports.Exports
import services.exports.summaries.TerminalSummaryLike
import services.graphstages.Crunch
import services.graphstages.Crunch.europeLondonTimeZone

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

trait ExportToCsv {
  self: Application =>

  import CsvFileStreaming._

  def exportToCsv(start: SDateLike,
                  end: SDateLike,
                  description: String,
                  terminal: Terminal,
                  maybeSummaryActorAndRequestProvider: Option[((SDateLike, Terminal) => ActorRef, Any)],
                  generateNewSummary: (SDateLike, SDateLike) => Future[TerminalSummaryLike])
                 (implicit timeout: Timeout): Result = {
    if (airportConfig.terminals.toSet.contains(terminal)) {
      val startString = start.millisSinceEpoch.toString
      val endString = end.millisSinceEpoch.toString
      val exportSource = exportBetweenDates(start, end, terminal, description, maybeSummaryActorAndRequestProvider, generateNewSummary)
      val fileName = makeFileName(description, terminal, start, end, airportConfig.portCode)

      Try(sourceToCsvResponse(exportSource, fileName)) match {
        case Success(value) => value
        case Failure(t) =>
          log.error("Failed to get CSV export", t)
          BadRequest("Failed to get CSV export")
      }
    } else {
      log.error(s"Bad terminal: $terminal")
      BadRequest(s"Invalid terminal $terminal")
    }
  }

  def exportBetweenDates(start: SDateLike,
                         end: SDateLike,
                         terminal: Terminal,
                         description: String,
                         maybeSummaryActorAndRequestProvider: Option[((SDateLike, Terminal) => ActorRef, Any)],
                         generateNewSummary: (SDateLike, SDateLike) => Future[TerminalSummaryLike])
                        (implicit timeout: Timeout): Source[String, NotUsed] = {
    val numberOfDays = start.daysBetweenInclusive(end)

    log.info(s"Export $description for terminal $terminal between ${start.toISOString()} & ${end.toISOString()} ($numberOfDays days)")

    Exports.summaryForDaysCsvSource(start, numberOfDays, now, terminal, maybeSummaryActorAndRequestProvider, generateNewSummary)
  }
}

object CsvFileStreaming {

  def sourceToCsvResponse(exportSource: Source[String, NotUsed], fileName: String): Result = {
    implicit val writeable: Writeable[String] = Writeable((str: String) => ByteString.fromString(str), Option("application/csv"))

    Result(
      header = ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
      body = HttpEntity.Chunked(exportSource.map(c => HttpChunk.Chunk(writeable.transform(c))), writeable.contentType))
  }

  def makeFileName(subject: String,
                   terminalName: Terminal,
                   startPit: SDateLike,
                   endPit: SDateLike,
                   portCode: PortCode): String = {
    val startLocal = SDate(startPit, Crunch.europeLondonTimeZone)
    val endLocal = SDate(endPit, Crunch.europeLondonTimeZone)
    val endDate = if (startLocal.daysBetweenInclusive(endLocal) > 1)
      f"-to-${endLocal.getFullYear()}-${endLocal.getMonth()}%02d-${endLocal.getDate()}%02d"
    else ""

    f"$portCode-$terminalName-$subject-" +
      f"${startLocal.getFullYear()}-${startLocal.getMonth()}%02d-${startLocal.getDate()}%02d" + endDate
  }

}
