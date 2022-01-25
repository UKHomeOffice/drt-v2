package actors.persistent

import uk.gov.homeoffice.drt.time.SDateLike


class CrunchQueueActor(now: () => SDateLike,
                       crunchOffsetMinutes: Int,
                       durationMinutes: Int) extends QueueLikeActor(now, crunchOffsetMinutes, durationMinutes) {
  override val persistenceId: String = "crunch-queue"
}

class DeploymentQueueActor(now: () => SDateLike,
                           crunchOffsetMinutes: Int,
                           durationMinutes: Int) extends QueueLikeActor(now, crunchOffsetMinutes, durationMinutes) {
  override val persistenceId: String = "deployment-queue"
}
