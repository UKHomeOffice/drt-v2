package services.exports

import drt.shared.StaffMovement
import drt.shared.Terminals.Terminal
import services.SDate

object StaffMovementsExport {

  def toCSV(movements: Seq[StaffMovement], terminal: Terminal) = movements.filter(_.terminal == terminal)
    .map { m =>
      s"${m.terminal},${m.reason},${SDate(m.time).toLocalDateTimeString()},${m.delta},${m.createdBy.getOrElse("")}"
    }.mkString("\n")

  def toCSVWithHeader(movements: Seq[StaffMovement], terminal: Terminal) = headerRow + "\n" + toCSV(movements, terminal)

  def headerRow = "Terminal,Reason,Time,Staff Change,Made by"

}
