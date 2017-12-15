package drt.client.components

import japgolly.scalajs.react._

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object HotTable {
  @JSImport("react-handsontable", "HotTable")
  @js.native
  object RawComponent extends js.Object

  @js.native
  trait Props extends js.Object {
    var data: Seq[Seq[String]] = js.native
  }

  def props(data: Seq[Seq[String]]): Props = {
    val p = (new js.Object).asInstanceOf[Props]
    p.data = data
    p
  }

  val component = JsComponent[Props, Children.None, Null](RawComponent)
}
//
//object TerminalStaffingV2 {
//  val log: Logger = LoggerFactory.getLogger(getClass.getName)
//  case class Props()
//
//  val component = ScalaComponent.builder[Props]("StaffingV2")
//    .render_P(_ => {
//      log.info("in here")
//      <.div(^.className := "container",
//        <.div(^.className := "col-md-3", HotTable.component(HotTable.props(Seq(Seq("yeah"))))))
////        <.div(^.className := "col-md-3", HotTable.component()))
//    })
//    .build
//
//  def apply() = component(Props())
//}