package spatutorial.shared

import java.util.UUID
import spatutorial.shared.FlightsApi.QueueName

case class StaffMovement(reason: String, time: MilliDate, delta: Int, uUID: UUID, queue: Option[QueueName] = None)
