package actors.minutes

import actors.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import akka.actor.ActorRef
import drt.shared.CrunchApi.StaffMinute
import drt.shared.{CrunchApi, SDateLike, TM}
import drt.shared.Terminals.Terminal

class StaffMinutesActor(terminals: Iterable[Terminal],
                        lookup: MinutesLookup[StaffMinute, TM],
                        updateMinutes: MinutesUpdate[StaffMinute, TM]) extends MinutesActorLike(terminals, lookup, updateMinutes) {
  override var maybeUpdatesSubscriber: Option[ActorRef] = None

  override def shouldSendAffects: CrunchApi.MinutesContainer[StaffMinute, TM] => Boolean = _ => true
}
