package providers

import actors.PartitionedPortStateActor
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import uk.gov.homeoffice.drt.arrivals.FlightsWithSplits
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

object FlightsProvider {
  def apply(flightsRouterActor: ActorRef)
           (implicit timeout: Timeout): (UtcDate, UtcDate, Terminal) => Source[(UtcDate, FlightsWithSplits), NotUsed] =
    (start, end, terminal) => {
      val startMillis = SDate(start).millisSinceEpoch
      val endMillis = SDate(end).addDays(1).addMinutes(-1).millisSinceEpoch
      Source.future(
        flightsRouterActor.ask(PartitionedPortStateActor.GetFlightsForTerminalDateRange(startMillis, endMillis, terminal))
          .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
      ).flatMapConcat(identity)
    }

}
