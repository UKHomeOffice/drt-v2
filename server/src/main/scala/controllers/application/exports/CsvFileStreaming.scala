package controllers.application.exports

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.http.{HttpChunk, HttpEntity, Writeable}
import play.api.mvc.{ResponseHeader, Result}
import uk.gov.homeoffice.drt.time.SDate
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate

object CsvFileStreaming {

  def csvFileResult(fileName: String, data: String): Result = Result(
    ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
    HttpEntity.Strict(ByteString(data), Option("application/csv")))

  def sourceToCsvResponse(exportSource: Source[String, NotUsed], fileName: String): Result = {
    val writeable: Writeable[String] = Writeable((str: String) => ByteString.fromString(str), Option("text/csv"))

    Result(
      header = ResponseHeader(200, Map("Content-Type" -> "text/csv", "Content-Disposition" -> s"attachment; filename=$fileName.csv")),
      body = HttpEntity.Chunked(exportSource.collect {
        case s if s.nonEmpty => s
      }.map(c => {
        HttpChunk.Chunk(writeable.transform(c))
      }), writeable.contentType))
  }

  def sourceToJsonResponse(exportSource: Source[String, NotUsed]): Result = {
    val writeable: Writeable[String] = Writeable((str: String) => ByteString.fromString(str), Option("application/json"))

    Result(
      header = ResponseHeader(200, Map("Content-Type" -> "application/json")),
      body = HttpEntity.Chunked(
        exportSource.collect {
          case s if s.nonEmpty => HttpChunk.Chunk(writeable.transform(s))
        },
        writeable.contentType
      )
    )
  }

  def makeFileName(subject: String,
                   maybeTerminal: Option[Terminal],
                   start: LocalDate,
                   end: LocalDate,
                   portCode: PortCode): String = {
    val startLocal = SDate(SDate(start), Crunch.europeLondonTimeZone)
    val endLocal = SDate(SDate(end), Crunch.europeLondonTimeZone)
    val endDate = if (startLocal.daysBetweenInclusive(endLocal) > 1)
      f"-to-${endLocal.getFullYear}-${endLocal.getMonth}%02d-${endLocal.getDate}%02d"
    else ""

    val terminalStr = maybeTerminal.map(t => s"${t.toString}-").getOrElse("")

    f"$portCode-$terminalStr$subject-" +
      f"${startLocal.getFullYear}-${startLocal.getMonth}%02d-${startLocal.getDate}%02d" + endDate
  }

}
