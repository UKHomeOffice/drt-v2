package controllers.application.exports

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.http.{HttpEntity, Writeable}
import play.api.mvc._
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

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

    val headers = Map(
      "Content-Type" -> mimeType,
    ) ++ maybeFileName.map(fn => "Content-Disposition" -> s"attachment; filename=$fn")

    Result(
      header = ResponseHeader(200, headers),
      body = HttpEntity.Streamed(byteStringStream, None, writeable.contentType)
    )
  }

  def makeFileName(subject: String,
                   terminal: Seq[Terminal],
                   start: SDateLike,
                   end: SDateLike,
                   portCode: PortCode): String = {
    val startLocal = SDate(start, europeLondonTimeZone)
    val endLocal = SDate(end, europeLondonTimeZone)
    val endDate = if (startLocal.daysBetweenInclusive(endLocal) > 1)
      f"-to-${endLocal.getFullYear}-${endLocal.getMonth}%02d-${endLocal.getDate}%02d"
    else ""

    val terminalStr = terminal.map(t => s"${t.toString}-").mkString

    f"$portCode-$terminalStr$subject-" +
      f"${startLocal.getFullYear}-${startLocal.getMonth}%02d-${startLocal.getDate}%02d" + endDate
  }

  def makeFileName(subject: String,
                   terminals: Seq[Terminal],
                   start: LocalDate,
                   end: LocalDate,
                   portCode: PortCode): String = {
    val startLocal = SDate(start)
    val endLocal = SDate(end)
    val endDate = if (startLocal.daysBetweenInclusive(endLocal) > 1)
      f"-to-${endLocal.getFullYear}-${endLocal.getMonth}%02d-${endLocal.getDate}%02d"
    else ""

    val terminalStr = terminals.map(t => s"${t.toString}-").mkString

    f"$portCode-$terminalStr$subject-" +
      f"${startLocal.getFullYear}-${startLocal.getMonth}%02d-${startLocal.getDate}%02d" + endDate
  }

}
