package actors.routing.minutes

import actors.routing.RouterActorLikeWithSubscriber
import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import drt.shared.CrunchApi.{MinutesContainer, StaffMinute}
import drt.shared.TM
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.UtcDate

class StaffMinutesActor(terminals: Iterable[Terminal],
                        lookup: MinutesLookup[StaffMinute, TM],
                        updateMinutes: MinutesUpdate[StaffMinute, TM]
                       )
  extends MinutesActorLike(terminals, lookup, updateMinutes)
    with RouterActorLikeWithSubscriber[MinutesContainer[StaffMinute, TM], (Terminal, UtcDate)] {
  override def shouldSendEffectsToSubscriber: MinutesContainer[StaffMinute, TM] => Boolean =
    _.contains(classOf[StaffMinute])
}
