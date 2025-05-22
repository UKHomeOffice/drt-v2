package drt.shared

import drt.shared.CrunchApi.MinutesContainer
import uk.gov.homeoffice.drt.models.{CrunchMinute, TQM}

case class SimulationMinutes(minutes: Seq[SimulationMinute]) extends PortStateQueueMinutes {
  override val asContainer: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(minutes)

  override def isEmpty: Boolean = minutes.isEmpty
}
