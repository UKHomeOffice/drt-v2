package actors.persistent

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.actor.commands.ProcessingRequest
import uk.gov.homeoffice.drt.time.SDateLike


class MergeArrivalsQueueActor(now: () => SDateLike,
                              processingRequest: MillisSinceEpoch => ProcessingRequest,
                             ) extends QueueLikeActor(now, processingRequest) {
  override val persistenceId: String = "merge-arrivals-queue"
}

class CrunchQueueActor(now: () => SDateLike,
                       processingRequest: MillisSinceEpoch => ProcessingRequest,
                      ) extends QueueLikeActor(now, processingRequest) {
  override val persistenceId: String = "crunch-queue"
}

class DeskRecsQueueActor(now: () => SDateLike,
                         processingRequest: MillisSinceEpoch => ProcessingRequest,
                        ) extends QueueLikeActor(now, processingRequest) {
  override val persistenceId: String = "desk-recs-queue"
}

class DeploymentQueueActor(now: () => SDateLike,
                           processingRequest: MillisSinceEpoch => ProcessingRequest,
                          ) extends QueueLikeActor(now, processingRequest) {
  override val persistenceId: String = "deployment-queue"
}

class StaffingUpdateQueueActor(now: () => SDateLike,
                               processingRequest: MillisSinceEpoch => ProcessingRequest,
                              ) extends QueueLikeActor(now, processingRequest) {
  override val persistenceId: String = "staffing-update-queue"
}
