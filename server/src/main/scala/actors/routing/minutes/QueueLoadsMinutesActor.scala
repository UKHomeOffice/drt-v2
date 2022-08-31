package actors.routing.minutes

import actors.routing.RouterActorLikeWithSubscriber
import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import drt.shared.CrunchApi.{MinutesContainer, PassengersMinute}
import drt.shared.TQM
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.UtcDate

class QueueLoadsMinutesActor(terminals: Iterable[Terminal],
                             lookup: MinutesLookup[PassengersMinute, TQM],
                             updateMinutes: MinutesUpdate[PassengersMinute, TQM])
  extends MinutesActorLike(terminals, lookup, updateMinutes)
    with RouterActorLikeWithSubscriber[MinutesContainer[PassengersMinute, TQM], (Terminal, UtcDate)] {
  override def shouldSendEffectsToSubscriber: MinutesContainer[PassengersMinute, TQM] => Boolean = _ => true
}
