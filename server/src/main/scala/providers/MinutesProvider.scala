package providers

import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.CrunchApi.MinutesContainer
import uk.gov.homeoffice.drt.arrivals.WithTimeAccessor
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.UtcDate

object MinutesProvider {
  def apply[A, B <: WithTimeAccessor](staffMinutesRouterActor: ActorRef)
                                     (implicit timeout: Timeout): (UtcDate, UtcDate, Terminal) => Source[(UtcDate, Seq[A]), NotUsed] =
    (start, end, terminal) => {
      Source
        .future(
          staffMinutesRouterActor.ask(actors.routing.minutes.GetStreamingMinutesForTerminalDateRange(terminal, start, end))
            .mapTo[Source[(UtcDate, MinutesContainer[A, B]), NotUsed]]
        )
        .flatMapConcat(identity)
        .map {
          case (date, container) => (date, container.minutes.map(_.toMinute).toSeq)
        }
    }
}
