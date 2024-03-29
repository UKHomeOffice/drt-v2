package services.exports

import drt.shared.StaffMovement
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDate

object StaffMovementsExport {

  def toCSV(movements: Seq[StaffMovement], terminal: Terminal): String = movements.filter(_.terminal == terminal)
    .map { m =>
      s"${m.terminal},${m.reason},${SDate(m.time).toLocalDateTimeString},${m.delta},${m.createdBy.getOrElse("")}"
    }.mkString("\n")

  def toCSVWithHeader(movements: Seq[StaffMovement], terminal: Terminal): String = headerRow + "\n" + toCSV(movements, terminal)

  private def headerRow = "Terminal,Reason,Time,Staff Change,Made by"

}
