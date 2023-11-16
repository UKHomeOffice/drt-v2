package providers

import actors.PartitionedPortStateActor
import actors.PartitionedPortStateActor.FlightsRequest
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

case class FlightsProvider(flightsRouterActor: ActorRef)
                          (implicit timeout: Timeout) {
  def singleTerminal: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    (start, end, terminal) => {
      val startMillis = SDate(start).millisSinceEpoch
      val endMillis = SDate(end).addDays(1).addMinutes(-1).millisSinceEpoch
      val request = PartitionedPortStateActor.GetFlightsForTerminals(startMillis, endMillis, Seq(terminal))
      flightsByUtcDate(request)
    }

  def allTerminals: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    (start, end) => {
      val startMillis = SDate(start).millisSinceEpoch
      val endMillis = SDate(end).addDays(1).addMinutes(-1).millisSinceEpoch
      val request = PartitionedPortStateActor.GetFlights(startMillis, endMillis)
      flightsByUtcDate(request)
    }

  private def flightsByUtcDate(request: FlightsRequest): Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] = {
    Source
      .future(flightsRouterActor.ask(request).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]])
      .flatMapConcat(identity)
      .map {
        case (date, flights) => (date, flights.flights.values.toSeq)
      }
  }
}
