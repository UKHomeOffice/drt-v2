package drt.client.components

import diode.AnyAction.aType
import drt.client.components.styles.{DrtTheme, WithScalaCssImplicits}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.RegisterDropIns
import drt.shared.DropIn
import io.kinoplan.scalajs.react.material.ui.core.system.{SxProps, ThemeProvider}
import io.kinoplan.scalajs.react.material.ui.core._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{BackendScope, Callback, CtorType, ReactEvent, ReactEventFromInput, ScalaComponent}

import scala.language.postfixOps


object DropInComponent extends WithScalaCssImplicits {

  case class State(confirmRegister: Boolean, selectedDropIn: String)

  case class Props(email: String, dropIns: Seq[DropIn])

  class Backend($: BackendScope[Props, State]) {
    def handleDropInSelection(id: String)(e: ReactEventFromInput) = {
      val currentlySelected = $.state.map(s => s.selectedDropIn).runNow()
      val newSelected = if (currentlySelected == id) "" else id
      $.modState(s => s.copy(selectedDropIn = newSelected))
    }


    def render(props: Props, state: State) = {
      def registerUser(e: ReactEvent) = {
        Callback(SPACircuit.dispatch(RegisterDropIns(state.selectedDropIn))) >>
          $.modState(s => s.copy(confirmRegister = true))
      }

      def back(e: ReactEvent) = {
        $.modState(s => s.copy(confirmRegister = false))
      }

      val confirmDropIn = <.div(
        MuiGrid(container = true, spacing = 2)(
          MuiGrid(item = true, xs = 12, sx = SxProps(Map(
            "backgroundColor" -> "#FFFFFF",
            "display" -> "flex",
            "justifyContent" -> "center",
            "alignItems" -> "center",
            "padding-top" -> "48px",
            "padding-bottom" -> "24px")))("Thanks for signing up for the drop-in. You will receive an email about it."),
          MuiGrid(item = true, xs = 12, sx = SxProps(Map(
            "display" -> "flex",
            "justifyContent" -> "center",
            "alignItems" -> "center",
            "padding-top" -> "24px",
            "padding-bottom" -> "24px")))(MuiButton(variant = "outlined")("Back", ^.onClick ==> back))))


      val showDropIns =
        ThemeProvider(DrtTheme.theme)(
          <.div(
            MuiGrid(container = true, spacing = 2, sx = SxProps(Map(
              "backgroundColor" -> "#FFFFFF",
            )))(
              MuiGrid(sx = SxProps(Map(
                "padding-top" -> "24px",
                "color" -> DrtTheme.theme.palette.primary.`700`,
                "font-size" -> DrtTheme.theme.typography.h3.fontSize,
                "font-weight" -> DrtTheme.theme.typography.h3.fontWeight
              )))(<.span(s"Register for a Drop-in")),
            )),
          MuiGrid(sx = SxProps(Map(
            "backgroundColor" -> "#FFFFFF",
            "padding-top" -> "24px",
            "padding-left" -> "24px",
            "padding-right" -> "24px",
            "overflow" -> "hidden"
          )))(
            MuiGrid(container = true, spacing = 2)(
              MuiGrid(item = true, xs = 12, sx = SxProps(Map(
                "backgroundColor" -> "#FFFFFF",
                "border" -> "8px solid #C0C7DE"
              )))(
                MuiPaper()(
                  MuiTable()(
                    MuiTableHead()(
                      MuiTableRow(sx =
                        SxProps(Map("backgroundColor" -> DrtTheme.theme.palette.primary.`50`)))(
                        MuiTableCell(sx = SxProps(Map("font-weight" -> "bold")))("Title"),
                        MuiTableCell(sx = SxProps(Map("font-weight" -> "bold")))("Start Time"),
                        MuiTableCell(sx = SxProps(Map("font-weight" -> "bold")))("End Time"),
                        MuiTableCell()(""),
                      )
                    ),
                    MuiTableBody()(
                      props.dropIns.zipWithIndex.toVdomArray {
                        case (tableItem, _) => MuiTableRow()(
                          MuiTableCell(component = "th", scope = "row")(tableItem.title),
                          MuiTableCell()(SDate(tableItem.startTime).prettyDateTime),
                          MuiTableCell()(SDate(tableItem.endTime).prettyDateTime),
                          MuiTableCell()(
                            MuiRadio()(
                              ^.checked := tableItem.id.exists(id => state.selectedDropIn.contains(id.toString)),
                              ^.onChange ==> handleDropInSelection(tableItem.id.getOrElse("").toString)
                            )),
                        )
                      }
                    )
                  )
                )
              ))
          ),
          <.div(
            MuiGrid(container = true, spacing = 2)(
              MuiGrid(item = true, xs = 12, sx = SxProps(Map(
                "backgroundColor" -> "#FFFFFF",
                "display" -> "flex",
                "justifyContent" -> "center",
                "alignItems" -> "center",
                "padding-top" -> "24px",
                "padding-bottom" -> "24px",
              )))(MuiButton(variant = "outlined")("Register", ^.onClick ==> registerUser))
            ))
        )
      if (state.confirmRegister) confirmDropIn else showDropIns
    }
  }

  val component: Component[Props, State, Backend, CtorType.Props] =
    ScalaComponent
      .builder[Props]("NavBar")
      .initialStateFromProps(_ => State(false, ""))
      .renderBackend[Backend]
      .build

  def apply(email: String,
            dropIns: Seq[DropIn]): VdomElement =
    component(Props(email, dropIns))

}
