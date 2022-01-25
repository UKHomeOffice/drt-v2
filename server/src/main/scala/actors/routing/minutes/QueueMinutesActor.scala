package actors.routing.minutes

import actors.routing.RouterActorLikeWithSubscriber
import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, MinutesContainer}
import drt.shared.TQM
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.UtcDate

import scala.language.postfixOps

class QueueMinutesActor(terminals: Iterable[Terminal],
                        lookup: MinutesLookup[CrunchMinute, TQM],
                        updateMinutes: MinutesUpdate[CrunchMinute, TQM])
  extends MinutesActorLike(terminals, lookup, updateMinutes) with RouterActorLikeWithSubscriber[MinutesContainer[CrunchMinute, TQM], (Terminal, UtcDate)] {

  override def shouldSendEffectsToSubscriber: MinutesContainer[CrunchMinute, TQM] => Boolean = _.contains(classOf[DeskRecMinute])
}
