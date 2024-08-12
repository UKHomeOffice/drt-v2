package drt.client.components

import drt.client.components.styles.{DrtTheme, WithScalaCssImplicits}
import drt.client.modules.GoogleEventTracker
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.{SxProps, ThemeProvider}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{BackendScope, Callback, CtorType, ReactEvent, ScalaComponent}
import io.kinoplan.scalajs.react.material.ui.core.{MuiDialog, MuiDialogContent, MuiDialogTitle}

object DropInDialog extends WithScalaCssImplicits {

  case class State(currentStep: Double)

  case class Props(date: String,
                   startTime: String,
                   duration: String,
                   showDialog: Boolean,
                   closeDialog: ReactEvent => Callback,
                   responseConfirm: ReactEvent => Callback,
                   title: String,
                   text: String,
                   action: String)


  class Backend($: BackendScope[Props, State]) {

    def centeredStyle: Map[String, String] = Map(
      "display" -> "flex",
      "justifyContent" -> "center",
      "alignItems" -> "center"
    )

    def render(props: Props, state: State) = {
      val headingColor = if (props.title == "Booking Confirmed")
        DrtTheme.theme.palette.grey.`400`
      else
        DrtTheme.theme.palette.primary.`500`

      ThemeProvider(DrtTheme.theme)(
        MuiDialog(open = props.showDialog, maxWidth = "sm")(
          <.div(
            MuiGrid(container = true, spacing = 0, sx = SxProps(Map(
              "backgroundColor" -> headingColor
            )))(
              MuiGrid(item = true, xs = 11)(
                MuiDialogTitle(sx = SxProps(centeredStyle ++ Map(
                  "color" -> "#FFFFFF",
                  "fontSize" -> DrtTheme.theme.typography.h3.fontSize,
                  "fontWeight" -> DrtTheme.theme.typography.h3.fontWeight
                )))(<.span(props.title))),
              MuiGrid(item = true, xs = 1)(
                MuiDialogActions()(
                  MuiIconButton(color = "#FFFFFF", sx = SxProps(centeredStyle ++ Map(
                    "color" -> "#FFFFFF"
                  )))(^.onClick ==> props.closeDialog, ^.aria.label := "Close")(
                    Icon.close))),
            ),
            MuiDialogContent(sx = SxProps(Map(
              "overflow" -> "hidden"
            )))(
              MuiGrid(container = true, spacing = 2, alignItems = "center")(
                MuiGrid(item = true, xs = 12, alignItems = "center", sx = SxProps(Map(
                  "backgroundColor" -> "#FFFFFF",
                  "padding" -> "16px",
                  "color" -> "black",
                )))(MuiGrid(container = true, spacing = 2, alignItems = "center")(
                  MuiGrid(item = true, xs = 12)(MuiDivider(sx = SxProps(centeredStyle ++ Map(
                    "color" -> "black"
                  )))()),
                  MuiGrid(item = true, xs = 4, sx = SxProps(centeredStyle ++ Map(
                    "paddingTop" -> "16px")))(props.date),
                  MuiGrid(item = true, xs = 4, sx = SxProps(centeredStyle ++ Map(
                    "paddingTop" -> "16px")))(props.startTime),
                  MuiGrid(item = true, xs = 4, sx = SxProps(centeredStyle ++ Map(
                    "paddingTop" -> "16px")))(props.duration),
                  MuiGrid(item = true, xs = 12)(MuiDivider(sx = SxProps(Map(
                    "paddingTop" -> "16px",
                    "color" -> "black",
                  )))()),
                )),
                MuiGrid(item = true, xs = 12, alignItems = "center", sx = SxProps(Map(
                  "backgroundColor" -> "#FFFFFF",
                  "padding" -> "16px",
                )))(props.text),
                MuiGrid(item = true, xs = 12, sx = SxProps(centeredStyle ++ Map(
                  "backgroundColor" -> "#FFFFFF"
                )))(MuiButton(variant = "contained")(^.onClick ==> props.responseConfirm, props.action)),
              )
            ))))
    }
  }

  val component: Component[Props, State, Backend, CtorType.Props] =
    ScalaComponent
      .builder[Props]("DropDialogComponent")
      .initialStateFromProps(_ => State(1))
      .renderBackend[Backend]
      .componentDidMount(_ => Callback(GoogleEventTracker.sendPageView("drop-dialog-component")))
      .build

  def apply(date: String,
            startTime: String,
            duration: String,
            showDialog: Boolean,
            closeDialog: ReactEvent => Callback,
            responseConfirm: ReactEvent => Callback,
            title: String,
            text: String,
            action: String): VdomElement =
    component(Props(date, startTime, duration, showDialog, closeDialog, responseConfirm, title: String, text: String, action: String))

}
