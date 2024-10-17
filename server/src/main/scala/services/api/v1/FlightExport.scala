package services.api.v1

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try


object FlightExport {

  case class FlightJson(code: String,
                        originPort: String,
                        scheduledTime: Long,
                        estimatedPcpStartTime: Option[Long],
                        estimatedPcpEndTime: Option[Long],
                        estimatedPaxCount: Option[Int],
                        status: String,
                       )

  object FlightJson {
    def apply(ar: Arrival)
             (implicit sourceOrderPreference: List[FeedSource]): FlightJson = FlightJson(
      ar.flightCodeString,
      ar.Origin.iata,
      ar.Scheduled,
      Try(ar.pcpRange(sourceOrderPreference).min).toOption,
      Try(ar.pcpRange(sourceOrderPreference).max).toOption,
      ar.bestPcpPaxEstimate(sourceOrderPreference),
      ar.Status.description,
    )
  }

  case class TerminalFlightsJson(terminal: Terminal, flights: Iterable[FlightJson])

  case class PortFlightsJson(portCode: PortCode, terminals: Iterable[TerminalFlightsJson])

  def apply(minutesSource: (SDateLike, SDateLike, Terminal) => Future[Seq[Arrival]],
            terminals: Iterable[Terminal],
            portCode: PortCode,
           )
           (implicit mat: Materializer, ec: ExecutionContext, sourceOrderPreference: List[FeedSource]): (SDateLike, SDateLike) => Future[PortFlightsJson] =
    (start, end) => {
      Source(terminals.toSeq)
        .mapAsync(terminals.size) { terminal =>
          minutesSource(start, end, terminal).map(_.map(FlightJson.apply(_)))
        }
        .runWith(Sink.seq)
        .map {
          terminalPeriods =>
            val terminalFlights = terminals.zip(terminalPeriods).map {
              case (terminal, periods) => TerminalFlightsJson(terminal, periods)
            }
            PortFlightsJson(portCode, terminalFlights)
        }
    }
}
