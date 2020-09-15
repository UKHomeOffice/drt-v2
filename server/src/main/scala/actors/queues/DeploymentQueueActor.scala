package actors.queues

import actors.StreamingJournalLike
import drt.shared.SDateLike


class DeploymentQueueActor(now: () => SDateLike, crunchOffsetMinutes: Int) extends QueueLikeActor(now, crunchOffsetMinutes) {
  override val persistenceId: String = "deployment-queue"
}
