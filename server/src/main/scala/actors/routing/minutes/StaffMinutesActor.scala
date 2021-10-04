package actors.routing.minutes

import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import drt.shared.CrunchApi.StaffMinute
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared.{CrunchApi, TM}

class StaffMinutesActor(terminals: Iterable[Terminal],
                        lookup: MinutesLookup[StaffMinute, TM],
                        updateMinutes: MinutesUpdate[StaffMinute, TM]
                       ) extends MinutesActorLike(terminals, lookup, updateMinutes) {
  override def shouldSendEffectsToSubscriber: CrunchApi.MinutesContainer[StaffMinute, TM] => Boolean = _ => true
}
