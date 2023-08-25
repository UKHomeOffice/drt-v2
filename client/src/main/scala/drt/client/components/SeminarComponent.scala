package drt.client.components

import diode.AnyAction.aType
import drt.client.components.styles.{DrtTheme, WithScalaCssImplicits}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.RegisterSeminars
import drt.shared.Seminar
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core.system.{SxProps, ThemeProvider}
import io.kinoplan.scalajs.react.material.ui.core._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{BackendScope, Callback, CtorType, ReactEvent, ReactEventFromInput, ScalaComponent}

import scala.language.postfixOps


object SeminarComponent extends WithScalaCssImplicits {

  case class State(currentStep: Double, selectedSeminars: Seq[String])

  case class Props(email: String, showDialog: Boolean, closeDialog: ReactEvent => Callback, seminars: Seq[Seminar])

  class Backend($: BackendScope[Props, State]) {

    def handleSeminarSelection(id: String)(e: ReactEventFromInput) = {
      val selectedSeminars: Seq[String] = $.state.map(s => s.selectedSeminars).runNow()
      val newSelectedSeminars = if (selectedSeminars.contains(id)) {
        selectedSeminars.filterNot(_ == id)
      } else {
        selectedSeminars :+ id
      }
      $.modState(s => s.copy(selectedSeminars = newSelectedSeminars))
    }

    def render(props: Props, state: State) = {
      def registerUser(e: ReactEvent) = {
        Callback(SPACircuit.dispatch(RegisterSeminars(state.selectedSeminars))) >>
          props.closeDialog(e)
      }

      val carouselItems =
        ThemeProvider(DrtTheme.theme)(
          MuiDialog(open = props.showDialog, maxWidth = "lg", scroll = "body", fullWidth = true)(
            <.div(
              MuiGrid(container = true, spacing = 2, sx = SxProps(Map(
                "backgroundColor" -> DrtTheme.theme.palette.primary.`50`,
              )))(
                MuiGrid(item = true, xs = 10)(
                  MuiDialogTitle(sx = SxProps(Map(
                    "color" -> DrtTheme.theme.palette.primary.`700`,
                    "font-size" -> DrtTheme.theme.typography.h2.fontSize,
                    "font-weight" -> DrtTheme.theme.typography.h2.fontWeight
                  )))(<.span(s"Register for a Seminar"))),
                MuiGrid(item = true, xs = 2)(
                  MuiDialogActions()(
                    MuiIconButton(color = Color.primary)(^.onClick ==> props.closeDialog, ^.aria.label := "Close")(
                      Icon.close))),
              )),
            MuiDialogContent(sx = SxProps(Map(
              "backgroundColor" -> DrtTheme.theme.palette.primary.`50`,
              "padding-top" -> "0px",
              "padding-left" -> "24px",
              "padding-right" -> "24px",
              "overflow" -> "hidden"
            )))(
              MuiGrid(container = true, spacing = 2)(
                MuiGrid(item = true, xs = 12, sx = SxProps(Map(
                  "backgroundColor" -> "#FFFFFF",
                  "border" -> "16px solid #C0C7DE"
                )))(
                  MuiPaper()(
                    MuiTable()(
                      MuiTableHead()(
                        MuiTableRow()(
                          MuiTableCell()("Title"),
                          MuiTableCell()("Description"),
                          MuiTableCell()("Start Time"),
                          MuiTableCell()("End Time"),
                        )
                      ),
                      MuiTableBody()(
                        props.seminars.zipWithIndex.toVdomArray {
                          case (tableItem, _) => MuiTableRow()(
                            MuiTableCell(component = "th", scope = "row")(tableItem.title),
                            MuiTableCell()(tableItem.description),
                            MuiTableCell()(SDate(tableItem.startTime).prettyDateTime),
                            MuiTableCell()(SDate(tableItem.endTime).prettyDateTime),
                            MuiTableCell()(
                              MuiCheckbox()(
                                ^.checked := tableItem.id.exists(id => state.selectedSeminars.contains(id.toString)),
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
                  "backgroundColor" -> DrtTheme.theme.palette.primary.`50`,
                  "display" -> "flex",
                  "justifyContent" -> "center",
                  "alignItems" -> "center",
                  "padding-bottom" -> "24px",
                )))(MuiDialogActions()(MuiButton()("Register", ^.onClick ==> registerUser)))
              ))
          ))
      carouselItems
    }
  }

  val component: Component[Props, State, Backend, CtorType.Props] =
    ScalaComponent
      .builder[Props]("NavBar")
      .initialStateFromProps(_ => State(1, Seq.empty[String]))
      .renderBackend[Backend]
      .componentDidMount(_ => Callback(GoogleEventTracker.sendPageView("seminars")))
      .build

  def apply(email: String,
            showDialog: Boolean,
            closeDialog: ReactEvent => Callback,
            seminars: Seq[Seminar]): VdomElement =
    component(Props(email, showDialog, closeDialog, seminars))

}
