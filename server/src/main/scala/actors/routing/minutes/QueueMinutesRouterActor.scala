package actors.routing.minutes

import actors.routing.RouterActorLikeWithSubscriber2
import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, MinutesContainer}
import drt.shared.TQM
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

object QueueMinutesRouterActor {
  def splitByResource(request: MinutesContainer[CrunchMinute, TQM]): Map[(Terminal, UtcDate), MinutesContainer[CrunchMinute, TQM]] = {
    request.minutes.groupBy(m => (m.terminal, SDate(m.minute).toUtcDate)).map {
      case ((terminal, date), minutes) => ((terminal, date), MinutesContainer(minutes))
    }
  }

  def sendIfDeskRec(request: MinutesContainer[CrunchMinute, TQM]): Boolean = request.minutes.exists(_.isInstanceOf[DeskRecMinute])
}

class QueueMinutesRouterActor(terminals: Iterable[Terminal],
                              lookup: MinutesLookup[CrunchMinute, TQM],
                              updateMinutes: MinutesUpdate[CrunchMinute, TQM])
  extends MinutesActorLike2(terminals, lookup, updateMinutes, QueueMinutesRouterActor.splitByResource, QueueMinutesRouterActor.sendIfDeskRec)
    with RouterActorLikeWithSubscriber2[MinutesContainer[CrunchMinute, TQM], (Terminal, UtcDate)] {
}
