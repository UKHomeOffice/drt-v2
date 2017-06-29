package drt.staff

import services.SDate
import services.SDate.implicits._

import scala.util.parsing.json.JSON

object ImportStaff
{
  def staffJsonToShifts(staffJson: String): String = {
    val parsed: Seq[String] = JSON.parseFull(staffJson) match {
      case Some(s: List[Map[String, Any]]) =>
        s.zipWithIndex.map{
          case (shift, index) =>
            val shiftStartDate = SDate.parseString(shift("dateTime").toString)
            val shiftsEndDate = shiftStartDate.addMinutes(15)
            f"shift$index, ${shift("name")}, ${shiftStartDate.ddMMyyString}, ${shiftStartDate.getHours()}%02d:${shiftStartDate.getMinutes()}%02d, ${shiftsEndDate.getHours()}%02d:${shiftsEndDate.getMinutes()}%02d, ${shift("staff")}"
        }
      case error =>
        println(s"got some bollocks: $error, $staffJson")
        Seq()
    }
    parsed.mkString("\n")
  }
}
