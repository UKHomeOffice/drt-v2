package actors.minutes

import actors.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import drt.shared.CrunchApi.StaffMinute
import drt.shared.{SDateLike, TM}
import drt.shared.Terminals.Terminal

class StaffMinutesActor(now: () => SDateLike,
                        terminals: Iterable[Terminal],
                        lookupPrimary: MinutesLookup[StaffMinute, TM],
                        lookupSecondary: MinutesLookup[StaffMinute, TM],
                        updateMinutes: MinutesUpdate[StaffMinute, TM]) extends MinutesActorLike(now, terminals, lookupPrimary, lookupSecondary, updateMinutes)
