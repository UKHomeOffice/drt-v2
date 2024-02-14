package providers

import actors.PartitionedPortStateActor
import actors.PartitionedPortStateActor.FlightsRequest
import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, FlightsWithSplits}
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

  def terminalLocalDate(implicit mat: Materializer): Terminal => LocalDate => Future[Seq[ApiFlightWithSplits]] =
    terminal => localDate => {
      val startMillis = SDate(localDate).millisSinceEpoch
      val endMillis = SDate(localDate).addDays(1).addMinutes(-1).millisSinceEpoch
      val request = PartitionedPortStateActor.GetFlightsForTerminals(startMillis, endMillis, Seq(terminal))
      flightsByUtcDate(request).map(_._2).runFold(Seq.empty[ApiFlightWithSplits])(_ ++ _)
    }


  def allTerminals: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    (start, end) => {
      val startMillis = SDate(start).millisSinceEpoch
      val endMillis = SDate(end).addDays(1).addMinutes(-1).millisSinceEpoch
      val request = PartitionedPortStateActor.GetFlights(startMillis, endMillis)
      flightsByUtcDate(request)
    }

  private def flightsByUtcDate(request: FlightsRequest): Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    Source
      .future(flightsRouterActor.ask(request).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]])
      .flatMapConcat(identity)
      .map {
        case (date, flights) => (date, flights.flights.values.toSeq)
      }
}
