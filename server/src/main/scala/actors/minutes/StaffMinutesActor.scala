package actors.minutes

import actors.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import drt.shared.CrunchApi.StaffMinute
import drt.shared.Terminals.Terminal
import drt.shared.{CrunchApi, TM}

class StaffMinutesActor(terminals: Iterable[Terminal],
                        lookup: MinutesLookup[StaffMinute, TM],
                        updateMinutes: MinutesUpdate[StaffMinute, TM]
                       ) extends MinutesActorLike(terminals, lookup, updateMinutes) {
  override def shouldSendEffectsToSubscriber: CrunchApi.MinutesContainer[StaffMinute, TM] => Boolean = _ => true
}
