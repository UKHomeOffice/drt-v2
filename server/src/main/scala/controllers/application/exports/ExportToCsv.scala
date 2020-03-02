package controllers.application.exports

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.scaladsl.Source
import akka.util.ByteString
import controllers.Application
import drt.shared.Terminals.Terminal
import drt.shared.{PortCode, PortState, SDateLike}
import play.api.http.{HttpChunk, HttpEntity, Writeable}
import play.api.mvc.{ResponseHeader, Result}
import services.SDate
import services.exports.Exports
import services.exports.summaries.TerminalSummaryLike
import services.graphstages.Crunch
import services.graphstages.Crunch.{europeLondonTimeZone, getLocalLastMidnight}

trait ExportToCsv {
  self: Application =>

  def exportToCsv(start: SDateLike,
                  end: SDateLike,
                  description: String,
                  terminal: Terminal,
                  maybeSummaryActorProvider: Option[(SDateLike, Terminal) => ActorRef],
                  summaryFromPortState: (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike]): Result = {
    if (airportConfig.terminals.toSet.contains(terminal)) {
      val startString = start.millisSinceEpoch.toString
      val endString = end.millisSinceEpoch.toString
      val exportSource = exportBetweenDates(startString, endString, terminal, description, maybeSummaryActorProvider, summaryFromPortState)
      val fileName = makeFileName(description, terminal, start, end, airportConfig.portCode)

      sourceToCsvResponse(exportSource, fileName)
    } else BadRequest(s"Invalid terminal $terminal")
  }

  def exportBetweenDates(start: String,
                         end: String,
                         terminal: Terminal,
                         description: String,
                         maybeSummaryActorProvider: Option[(SDateLike, Terminal) => ActorRef],
                         summaryFromPortStateProvider: (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike]): Source[String, NotUsed] = {
    val startPit = getLocalLastMidnight(SDate(start.toLong, europeLondonTimeZone))
    val endPit = getLocalLastMidnight(SDate(end.toLong, europeLondonTimeZone))
    val numberOfDays = ((endPit.millisSinceEpoch - startPit.millisSinceEpoch).toInt / Crunch.oneDayMillis) + 1

    log.info(s"Export $description for terminal $terminal between ${SDate(start.toLong).toISOString()} & ${SDate(end.toLong).toISOString()}")

    Exports.summaryForDaysCsvSource(startPit, numberOfDays, now, terminal, maybeSummaryActorProvider, queryPortStateActor, summaryFromPortStateProvider)
  }

  def makeFileName(subject: String,
                   terminalName: Terminal,
                   startPit: SDateLike,
                   endPit: SDateLike,
                   portCode: PortCode): String = {
    val endDate = if (startPit != endPit)
      f"-to-${endPit.getFullYear()}-${endPit.getMonth()}%02d-${endPit.getDate()}%02d"
    else ""

    f"$portCode-$terminalName-$subject-" +
      f"${startPit.getFullYear()}-${startPit.getMonth()}%02d-${startPit.getDate()}%02d" + endDate
  }

  def sourceToCsvResponse(exportSource: Source[String, NotUsed], fileName: String): Result = {
    implicit val writeable: Writeable[String] = Writeable((str: String) => ByteString.fromString(str), Option("application/csv"))

    Result(
      header = ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
      body = HttpEntity.Chunked(exportSource.map(c => HttpChunk.Chunk(writeable.transform(c))), writeable.contentType))
  }
}
