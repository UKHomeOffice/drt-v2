package drt.shared

import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer}

case class SimulationMinutes(minutes: Seq[SimulationMinute]) extends PortStateQueueMinutes {
  override val asContainer: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(minutes)

  override def isEmpty: Boolean = minutes.isEmpty
}
