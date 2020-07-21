package drt.shared

import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer}

case class SimulationMinutes(minutes: Seq[SimulationMinute]) extends PortStateQueueMinutes {
  override val asContainer: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(minutes)

  override def isEmpty: Boolean = minutes.isEmpty

  override def applyTo(portState: PortStateMutable, now: MillisSinceEpoch): PortStateDiff = {
    val minutesDiff = minutes.foldLeft(List[CrunchMinute]()) { case (soFar, dm) =>
      addIfUpdated(portState.crunchMinutes.getByKey(dm.key), now, soFar, dm, () => dm.toUpdatedMinute(now))
    }
    portState.crunchMinutes +++= minutesDiff
    PortStateDiff(Seq(), Seq(), Seq(), minutesDiff, Seq())
  }
}
