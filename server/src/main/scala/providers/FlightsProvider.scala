package providers

import actors.PartitionedPortStateActor
import actors.PartitionedPortStateActor.{DateRangeMillisLike, PointInTimeQuery}
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.concurrent.Future

case class FlightsProvider(flightsRouterActor: ActorRef)
                          (implicit timeout: Timeout) {
  def singleTerminal: Terminal => (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    terminal => (start, end) => {
      val startMillis = SDate(start).millisSinceEpoch
      val endMillis = SDate(end).addDays(1).addMinutes(-1).millisSinceEpoch
      val request = PartitionedPortStateActor.GetFlightsForTerminals(startMillis, endMillis, Seq(terminal))
      flightsByUtcDate(request)
    }

  def terminalLocalDate(implicit mat: Materializer): Terminal => (LocalDate, Option[Long]) => Future[Seq[ApiFlightWithSplits]] =
    terminal => (localDate, maybePointInTime) => {
      val startMillis = SDate(localDate).millisSinceEpoch
      val endMillis = SDate(localDate).addDays(1).addMinutes(-1).millisSinceEpoch
      val request = PartitionedPortStateActor.GetFlightsForTerminals(startMillis, endMillis, Seq(terminal))
      val finalRequest = maybePointInTime match {
        case Some(pointInTime) => PointInTimeQuery(pointInTime, request)
        case None => request
      }
      flightsByUtcDate(finalRequest).map(_._2).runFold(Seq.empty[ApiFlightWithSplits])(_ ++ _)
    }

  def allTerminalsDateRange: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    (start, end) => {
      val startMillis = SDate(start).millisSinceEpoch
      val endMillis = SDate(end).addDays(1).addMinutes(-1).millisSinceEpoch
      val request = PartitionedPortStateActor.GetFlights(startMillis, endMillis)
      flightsByUtcDate(request)
    }

  def allTerminalsSingleDate: UtcDate => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    date => allTerminalsDateRange(date, date)

  private def flightsByUtcDate(request: DateRangeMillisLike): Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    Source
      .future(flightsRouterActor.ask(request).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]])
      .flatMapConcat(identity)
      .map {
        case (date, flights) => (date, flights.flights.values.toSeq)
      }
}
