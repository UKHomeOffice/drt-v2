package actors.persistent

import drt.shared.SDateLike


class DeploymentQueueActor(now: () => SDateLike,
                           crunchOffsetMinutes: Int,
                           durationMinutes: Int) extends QueueLikeActor(now, crunchOffsetMinutes, durationMinutes) {
  override val persistenceId: String = "deployment-queue"
}
