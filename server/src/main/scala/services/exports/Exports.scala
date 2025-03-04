package services.exports

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import controllers.application.exports.CsvFileStreaming.makeFileName
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import play.api.http.Status.OK
import play.api.http.{HttpChunk, HttpEntity, Writeable}
import play.api.mvc.{ResponseHeader, Result, Results}
import play.mvc.StaticFileMimeTypes.fileMimeTypes
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Splits}
import uk.gov.homeoffice.drt.ports.{PortCode, SplitRatiosNs}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone

import scala.jdk.OptionConverters.RichOptional


object Exports {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def millisToLocalIsoDateOnly: MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate.millisToLocalIsoDateOnly(europeLondonTimeZone)(millis)

  def millisToLocalDateTimeString: MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate(millis, europeLondonTimeZone).toLocalDateTimeString

  def actualAPISplitsAndHeadingsFromFlight(flightWithSplits: ApiFlightWithSplits): Set[(String, Double)] = flightWithSplits
    .splits
    .collect {
      case s: Splits if s.source == SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages =>
        s.splits.map(s => (s"API Actual - ${s.paxTypeAndQueue.displayName}", s.paxCount))
    }
    .flatten

  def streamExport(portCode: PortCode,
                   terminals: Seq[Terminal],
                   start: LocalDate,
                   end: LocalDate,
                   stream: Source[String, NotUsed],
                   exportName: String): Result = {
    implicit val writeable: Writeable[String] = Writeable(ByteString.fromString, Option("text/csv"))

    val header = ResponseHeader(OK)
    val disableNginxProxyBuffering = "X-Accel-Buffering" -> "no"
    val fileName = makeFileName(exportName, terminals, start, end, portCode) + ".csv"

    Result(
      header = header.copy(headers = header.headers ++ Results.contentDispositionHeader(inline = true, Option(fileName)) ++ Option(disableNginxProxyBuffering)),
      body = HttpEntity.Chunked(
        stream.map(c => HttpChunk.Chunk(writeable.transform(c))),
        fileMimeTypes.forFileName(fileName).toScala
      )
    )
  }

}
