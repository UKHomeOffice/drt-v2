package drt.shared

import java.util.UUID

import drt.shared.FlightsApi.{QueueName, TerminalName}

case class StaffMovement(terminalName: TerminalName = "",
                         reason: String,
                         time: MilliDate,
                         delta: Int,
                         uUID: UUID,
                         queue: Option[QueueName] = None,
                         createdBy: Option[String])

