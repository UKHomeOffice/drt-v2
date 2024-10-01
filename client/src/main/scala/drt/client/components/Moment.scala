package drt.client.components

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("moment", JSImport.Namespace)
object Moment extends js.Object {
  def apply(): Moment = js.native
  def apply(string: String): Moment = js.native
  def apply(date: js.Date): Moment = js.native

}

@js.native
trait Moment extends js.Object {
  def toDate(): js.Date = js.native
}

// Create a Moment.js date
//val momentDate: Moment = Moment()
//
//// Convert the Moment.js date to a JavaScript Date
//val jsDate: js.Date = momentDate.toDate()
//
//// Convert the JavaScript Date to an SDate
//val sDate: SDate = SDate(jsDate)
//
//// Assign the SDate to startAt
//startAt = sDate


