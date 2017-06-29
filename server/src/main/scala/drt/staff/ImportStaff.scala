package drt.staff

import org.joda.time.{DateTime, DateTimeZone}
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
            //The client deals in local time, and these shifts are sent to the client as strings with no timezone for now.
            //TODO: store shifts not as strings.
            val shiftStartDate = new DateTime(shift("dateTime").toString).withZone(DateTimeZone.forID("Europe/London"))
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
