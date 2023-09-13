package drt.client.components

import diode.AnyAction.aType
import drt.client.components.styles.{DrtTheme, WithScalaCssImplicits}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.RegisterSeminars
import drt.shared.Seminar
import io.kinoplan.scalajs.react.material.ui.core.system.{SxProps, ThemeProvider}
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, _}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{BackendScope, Callback, CtorType, ReactEvent, ReactEventFromInput, ScalaComponent}

import scala.language.postfixOps


object SeminarComponent extends WithScalaCssImplicits {

  case class State(confirmRegister: Boolean, selectedSeminar: String)

  case class Props(email: String, seminars: Seq[Seminar])

  class Backend($: BackendScope[Props, State]) {
    def handleSeminarSelection(id: String)(e: ReactEventFromInput) = {
      val currentlySelected = $.state.map(s => s.selectedSeminar).runNow()
      val newSelected = if (currentlySelected == id) "" else id
      $.modState(s => s.copy(selectedSeminar = newSelected))
    }


    def render(props: Props, state: State) = {
      def registerUser(e: ReactEvent) = {
        Callback(SPACircuit.dispatch(RegisterSeminars(state.selectedSeminar))) >>
          $.modState(s => s.copy(confirmRegister = true))
      }

      def back(e: ReactEvent) = {
        $.modState(s => s.copy(confirmRegister = false))
      }

      val confirmSeminar = <.div(
        MuiGrid(container = true, spacing = 2)(
          MuiGrid(item = true, xs = 12, sx = SxProps(Map(
            "backgroundColor" -> "#FFFFFF",
            "display" -> "flex",
            "justifyContent" -> "center",
            "alignItems" -> "center",
            "padding-top" -> "48px",
            "padding-bottom" -> "24px")))("Thanks for signing up for the seminar. You will receive an email about it."),
          MuiGrid(item = true, xs = 12, sx = SxProps(Map(
            "display" -> "flex",
            "justifyContent" -> "center",
            "alignItems" -> "center",
            "padding-top" -> "24px",
            "padding-bottom" -> "24px")))(MuiButton(variant = "outlined")("Back", ^.onClick ==> back))))


      val showSeminars =
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
                      props.seminars.zipWithIndex.toVdomArray {
                        case (tableItem, _) => MuiTableRow()(
                          MuiTableCell(component = "th", scope = "row")(tableItem.title),
                          MuiTableCell()(SDate(tableItem.startTime).prettyDateTime),
                          MuiTableCell()(SDate(tableItem.endTime).prettyDateTime),
                          MuiTableCell()(
                            MuiRadio()(
                              ^.checked := tableItem.id.exists(id => state.selectedSeminar.contains(id.toString)),
                              ^.onChange ==> handleSeminarSelection(tableItem.id.getOrElse("").toString)
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
      if (state.confirmRegister) confirmSeminar else showSeminars
    }
  }

  val component: Component[Props, State, Backend, CtorType.Props] =
    ScalaComponent
      .builder[Props]("NavBar")
      .initialStateFromProps(_ => State(false, ""))
      .renderBackend[Backend]
      .build

  def apply(email: String,
            seminars: Seq[Seminar]): VdomElement =
    component(Props(email, seminars))

}
