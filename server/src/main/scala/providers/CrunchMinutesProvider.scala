package providers

import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer}
import drt.shared.TQM
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.UtcDate

object CrunchMinutesProvider {
  def apply(crunchMinutesRouterActor: ActorRef)
           (implicit timeout: Timeout): (UtcDate, UtcDate, Terminal) => Source[(UtcDate, Seq[CrunchMinute]), NotUsed] =
    (start, end, terminal) => {
      Source
        .future(
          crunchMinutesRouterActor.ask(actors.routing.minutes.GetStreamingDesksForTerminalDateRange(terminal, start, end))
            .mapTo[Source[(UtcDate, MinutesContainer[CrunchMinute, TQM]), NotUsed]]
        )
        .flatMapConcat(identity)
        .map {
          case (date, container) => (date, container.minutes.map(_.toMinute).toSeq)
        }
    }
}
