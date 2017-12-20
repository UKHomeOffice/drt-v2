package drt.client.components

import diode.data.Pot
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{StaffAssignmentParser, StaffAssignmentServiceWithDates}
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.util.Success

object HotTable {

  @JSImport("react-handsontable", JSImport.Default)
  @js.native
  object RawComponent extends js.Object

  @js.native
  trait Props extends js.Object {
    var settings: js.Dictionary[js.Any] = js.native
  }

  def props(data: Seq[Seq[String]], colHeadings: Seq[String], rowHeadings: Seq[String]): Props = {
    import js.JSConverters._
    val p = (new js.Object).asInstanceOf[Props]
    p.settings = js.Dictionary(
      "data" -> data.map(_.toJSArray).toJSArray,
      "rowHeaders" -> rowHeadings.toJSArray,
      "colHeaders" -> colHeadings.toJSArray
    )
    p
  }

  val component = JsComponent[Props, Children.None, Null](RawComponent)
}

object TerminalStaffingV2 {
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(
                    rawShiftString: String
  )

  val now = SDate.now()
  val firstOfMonth = SDate(now.getFullYear(), now.getMonth(), 1)



  val component = ScalaComponent.builder[Props]("StaffingV2")
    .render_P(p => {
      val shifts = StaffAssignmentParser(p.rawShiftString).parsedAssignments.toList.collect{
        case Success(s) => s
      }
      val ss: StaffAssignmentServiceWithDates = StaffAssignmentServiceWithDates(shifts)
      HotTable.component(HotTable.props(Seq(Seq("yeah")), Seq("yeah"), Seq("yeah")))
    })
    .build

  def apply(rawShiftString: String) = component(Props(rawShiftString))
}
