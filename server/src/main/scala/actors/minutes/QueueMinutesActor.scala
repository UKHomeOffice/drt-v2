package actors.minutes

import actors.SetDeploymentQueueActor
import actors.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import actors.queues.QueueLikeActor
import actors.queues.QueueLikeActor.UpdatedMillis
import akka.actor.ActorRef
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, MinutesContainer}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TQM}

import scala.concurrent.Future
import scala.language.postfixOps

class QueueMinutesActor(terminals: Iterable[Terminal],
                        lookup: MinutesLookup[CrunchMinute, TQM],
                        updateMinutes: MinutesUpdate[CrunchMinute, TQM])
  extends MinutesActorLike(terminals, lookup, updateMinutes) {

  override var maybeUpdatesSubscriber: Option[ActorRef] = None

  override def shouldSendAffects: MinutesContainer[CrunchMinute, TQM] => Boolean = _.contains(classOf[DeskRecMinute])
}
