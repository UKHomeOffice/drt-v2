package spatutorial.shared

import java.util.UUID

import spatutorial.shared.FlightsApi.{QueueName, TerminalName}

case class StaffMovement(
                          terminalName: TerminalName = "",
                          reason: String,
                          time: MilliDate,
                          delta: Int,
                          uUID: UUID,
                          queue: Option[QueueName] = None
                        )

