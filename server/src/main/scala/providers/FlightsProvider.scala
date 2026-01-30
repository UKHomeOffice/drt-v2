package providers

import actors.PartitionedPortStateActor
import actors.PartitionedPortStateActor.{DateRangeMillisLike, PointInTimeQuery}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

case class FlightsProvider(flightsRouterActor: ActorRef)
                          (implicit timeout: Timeout) {
  def terminalDateScheduledOrPcp(implicit mat: Materializer): Terminal => (LocalDate, Option[Long]) => Future[Seq[ApiFlightWithSplits]] =
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

  def terminalDateScheduled(implicit mat: Materializer, ec: ExecutionContext): Terminal => (LocalDate, Option[Long]) => Future[Seq[ApiFlightWithSplits]] =
    terminal => (localDate, maybePointInTime) => {
      val start = SDate(localDate).millisSinceEpoch
      val end = SDate(localDate).addDays(1).millisSinceEpoch

      terminalDateScheduledOrPcp(mat)(terminal)(localDate, maybePointInTime).map(_.filter { fws =>
        fws.apiFlight.Scheduled >= start && fws.apiFlight.Scheduled < end
      })
    }

  def terminalScheduledOn: Terminal => UtcDate => Source[Seq[ApiFlightWithSplits], NotUsed] =
    terminal => date => {
      val start = SDate(date)
      val end = start.addDays(1).addMinutes(-1)
      val request = PartitionedPortStateActor.GetFlightsForTerminals(start.millisSinceEpoch, end.millisSinceEpoch, Seq(terminal))
      flightsByUtcDate(request)
        .map { case (_, flights) => flights.filter(f => SDate(f.apiFlight.Scheduled).toUtcDate == date) }
    }

  def allTerminalsScheduledOn: UtcDate => Source[Seq[ApiFlightWithSplits], NotUsed] =
    date => {
      val start = SDate(date)
      val end = start.addDays(1).addMinutes(-1)
      val request = PartitionedPortStateActor.GetFlights(start.millisSinceEpoch, end.millisSinceEpoch)
      flightsByUtcDate(request)
        .map { case (_, flights) => flights.filter(f => SDate(f.apiFlight.Scheduled).toUtcDate == date) }
    }

  def allTerminalsDateRangeScheduledOrPcp: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    (start, end) => {
      val startMillis = SDate(start).millisSinceEpoch
      val endMillis = SDate(end).addDays(1).addMinutes(-1).millisSinceEpoch
      val request = PartitionedPortStateActor.GetFlights(startMillis, endMillis)
      flightsByUtcDate(request)
    }

  def allTerminalsDateScheduledOrPcp: UtcDate => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    date => allTerminalsDateRangeScheduledOrPcp(date, date)

  private def flightsByUtcDate(request: DateRangeMillisLike): Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    Source
      .future(flightsRouterActor.ask(request).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]])
      .flatMapConcat(identity)
      .map {
        case (date, flights) => (date, flights.flights.values.toSeq)
      }
}
