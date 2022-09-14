package actors.routing.minutes

import actors.routing.RouterActorLikeWithSubscriber2
import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, MinutesContainer, PassengersMinute}
import drt.shared.TQM
import services.SDate
import uk.gov.homeoffice.drt.arrivals.WithTimeAccessor
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.UtcDate

object QueueLoadsMinutesActor {
  def splitByResource(request: MinutesContainer[PassengersMinute, TQM]): Map[(Terminal, UtcDate), MinutesContainer[PassengersMinute, TQM]] = {
    request.minutes.groupBy(m => (m.terminal, SDate(m.minute).toUtcDate)).map {
      case ((terminal, date), minutes) => ((terminal, date), MinutesContainer(minutes))
    }
  }

  def alwaysSend(request: MinutesContainer[PassengersMinute, TQM]): Boolean = true
}

class QueueLoadsMinutesActor(terminals: Iterable[Terminal],
                             lookup: MinutesLookup[PassengersMinute, TQM],
                             updateMinutes: MinutesUpdate[PassengersMinute, TQM])
  extends MinutesActorLike2(
    terminals,
    lookup,
    updateMinutes,
    QueueLoadsMinutesActor.splitByResource,
    QueueLoadsMinutesActor.alwaysSend,
  ) with RouterActorLikeWithSubscriber2[MinutesContainer[PassengersMinute, TQM], (Terminal, UtcDate)]
