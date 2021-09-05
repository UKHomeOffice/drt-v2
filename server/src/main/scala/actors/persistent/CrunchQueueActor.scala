package actors.persistent

import drt.shared.SDateLike


abstract class CrunchQueueActor(now: () => SDateLike,
                                crunchOffsetMinutes: Int,
                                durationMinutes: Int) extends QueueLikeActor(now, crunchOffsetMinutes, durationMinutes) {
  override val persistenceId: String = "crunch-queue"
}
