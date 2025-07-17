package actors.routing.minutes

import actors.routing.RouterActorLikeWithSubscriber
import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import drt.shared.CrunchApi.{MinutesContainer, StaffMinute}
import drt.shared.TM
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, UtcDate}

class StaffMinutesRouterActor(terminalsForDateRange: (LocalDate, LocalDate) => Iterable[Terminal],
                              lookup: MinutesLookup[StaffMinute, TM],
                              updateMinutes: MinutesUpdate[StaffMinute, TM, TerminalUpdateRequest]
                       )
  extends MinutesActorLike(terminalsForDateRange, lookup, updateMinutes)
    with RouterActorLikeWithSubscriber[MinutesContainer[StaffMinute, TM], (Terminal, UtcDate), TerminalUpdateRequest] {
  override def shouldSendEffectsToSubscriber: MinutesContainer[StaffMinute, TM] => Boolean =
    _.contains(classOf[StaffMinute])
}
