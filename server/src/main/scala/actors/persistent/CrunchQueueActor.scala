package actors.persistent

import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}


class MergeArrivalsQueueActor(now: () => SDateLike, terminals: LocalDate => Iterable[Terminal]) extends QueueLikeActor(now, terminals) {
  override val persistenceId: String = "merge-arrivals-queue"
}

class CrunchQueueActor(now: () => SDateLike, terminals: LocalDate => Iterable[Terminal]) extends QueueLikeActor(now, terminals) {
  override val persistenceId: String = "crunch-queue"
}

class DeskRecsQueueActor(now: () => SDateLike, terminals: LocalDate => Iterable[Terminal]) extends QueueLikeActor(now, terminals) {
  override val persistenceId: String = "desk-recs-queue"
}

class DeploymentQueueActor(now: () => SDateLike, terminals: LocalDate => Iterable[Terminal]) extends QueueLikeActor(now, terminals) {
  override val persistenceId: String = "deployment-queue"
}

class StaffingUpdateQueueActor(now: () => SDateLike, terminals: LocalDate => Iterable[Terminal]) extends QueueLikeActor(now, terminals) {
  override val persistenceId: String = "staffing-update-queue"
}
