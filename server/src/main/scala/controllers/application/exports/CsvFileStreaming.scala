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
    implicit val writeable: Writeable[String] = Writeable((str: String) => ByteString.fromString(str), Option("application/csv"))

    Result(
      header = ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
      body = HttpEntity.Chunked(exportSource.collect {
        case s if s.nonEmpty => s
      }.map(c => {
        HttpChunk.Chunk(writeable.transform(c))
      }), writeable.contentType))
  }

  def makeFileName(subject: String,
                   terminalName: Terminal,
                   start: LocalDate,
                   end: LocalDate,
                   portCode: PortCode): String = {
    val startLocal = SDate(SDate(start), Crunch.europeLondonTimeZone)
    val endLocal = SDate(SDate(end), Crunch.europeLondonTimeZone)
    val endDate = if (startLocal.daysBetweenInclusive(endLocal) > 1)
      f"-to-${endLocal.getFullYear}-${endLocal.getMonth}%02d-${endLocal.getDate}%02d"
    else ""

    f"$portCode-$terminalName-$subject-" +
      f"${startLocal.getFullYear}-${startLocal.getMonth}%02d-${startLocal.getDate}%02d" + endDate
  }

}
