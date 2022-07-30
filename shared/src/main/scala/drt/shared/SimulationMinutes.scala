package drt.shared

import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer}

case class SimulationMinutes(minutes: Iterable[SimulationMinute]) extends PortStateQueueMinutes {
  override val asContainer: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(minutes)

  override def isEmpty: Boolean = minutes.isEmpty
}
