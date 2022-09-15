package actors.persistent

import uk.gov.homeoffice.drt.time.SDateLike


class CrunchQueueActor(now: () => SDateLike,
                       crunchOffsetMinutes: Int,
                       durationMinutes: Int) extends QueueLikeActor(now, crunchOffsetMinutes, durationMinutes) {
  override val persistenceId: String = "crunch-queue"
}

class DeskRecsQueueActor(now: () => SDateLike,
                         crunchOffsetMinutes: Int,
                         durationMinutes: Int) extends QueueLikeActor(now, crunchOffsetMinutes, durationMinutes) {
  override val persistenceId: String = "desk-recs-queue"
}

class DeploymentQueueActor(now: () => SDateLike,
                           crunchOffsetMinutes: Int,
                           durationMinutes: Int) extends QueueLikeActor(now, crunchOffsetMinutes, durationMinutes) {
  override val persistenceId: String = "deployment-queue"
}

class StaffingUpdateQueueActor(now: () => SDateLike,
                               crunchOffsetMinutes: Int,
                               durationMinutes: Int) extends QueueLikeActor(now, crunchOffsetMinutes, durationMinutes) {
  override val persistenceId: String = "staffing-update-queue"
}
