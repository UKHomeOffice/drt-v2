package controllers.application.exports

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.http.{HttpEntity, Writeable}
import play.api.mvc.{ResponseHeader, Result}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

object CsvFileStreaming {

  def csvFileResult(fileName: String, data: String): Result = Result(
    ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
    HttpEntity.Strict(ByteString(data), Option("application/csv")))

  def sourceToCsvResponse(exportSource: Source[String, NotUsed], fileName: String): Result =
    streamingResponse(exportSource, "text/csv", Option(fileName))

  def sourceToJsonResponse(exportSource: Source[String, NotUsed]): Result =
    streamingResponse(exportSource, "application/json", None)

  private def streamingResponse(exportSource: Source[String, NotUsed], mimeType: String, maybeFileName: Option[String]): Result = {
    val writeable: Writeable[String] = Writeable((str: String) => ByteString.fromString(str), Option(mimeType))

    val byteStringStream = exportSource.collect {
      case s if s.nonEmpty => writeable.transform(s)
    }

    Result(
      header = ResponseHeader(200, Map("Content-Type" -> "application/json") ++ maybeFileName.map(fn => "Content-Disposition" -> s"attachment; filename=$fn.csv")),
      body = HttpEntity.Streamed(byteStringStream, None, writeable.contentType)
    )
  }

  def makeFileName(subject: String,
                   maybeTerminal: Option[Terminal],
                   start: LocalDate,
                   end: LocalDate,
                   portCode: PortCode): String = {
    val startLocal = SDate(SDate(start), europeLondonTimeZone)
    val endLocal = SDate(SDate(end), europeLondonTimeZone)
    val endDate = if (startLocal.daysBetweenInclusive(endLocal) > 1)
      f"-to-${endLocal.getFullYear}-${endLocal.getMonth}%02d-${endLocal.getDate}%02d"
    else ""

    val terminalStr = maybeTerminal.map(t => s"${t.toString}-").getOrElse("")

    f"$portCode-$terminalStr$subject-" +
      f"${startLocal.getFullYear}-${startLocal.getMonth}%02d-${startLocal.getDate}%02d" + endDate
  }

}
