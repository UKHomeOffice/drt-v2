package actors.minutes

import actors.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import drt.shared.CrunchApi.StaffMinute
import drt.shared.{SDateLike, TM}
import drt.shared.Terminals.Terminal

class StaffMinutesActor(now: () => SDateLike,
                        terminals: Iterable[Terminal],
                        lookup: MinutesLookup[StaffMinute, TM],
                        lookupLegacy: MinutesLookup[StaffMinute, TM],
                        updateMinutes: MinutesUpdate[StaffMinute, TM]) extends MinutesActorLike(now, terminals, lookup, lookupLegacy, updateMinutes)
