package actors.queues

import actors.StreamingJournalLike
import drt.shared.SDateLike


class CrunchQueueActor(now: () => SDateLike,
                       journalType: StreamingJournalLike,
                       crunchOffsetMinutes: Int,
                       durationMinutes: Int) extends QueueLikeActor(now,  crunchOffsetMinutes, durationMinutes) {
  override val persistenceId: String = "crunch-queue"
}
