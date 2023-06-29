package drt.client.components

import drt.client.SPAMain
import drt.client.components.styles.{DrtTheme, WithScalaCssImplicits}
import drt.client.modules.GoogleEventTracker
import drt.client.services.DrtApi
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.{SxProps, ThemeProvider}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{BackendScope, Callback, CtorType, ReactEvent, ScalaComponent}
import uk.gov.homeoffice.drt.training.TrainingData
import upickle.default.write

import scala.language.postfixOps
import io.kinoplan.scalajs.react.material.ui.core.{MuiDialog, MuiDialogContent, MuiDialogTitle}


object TrainingModalComponent extends WithScalaCssImplicits {

  case class State(currentStep: Double)

  case class Props(showDialog: Boolean, closeDialog: ReactEvent => Callback, trainingDataTemplates: Seq[TrainingData])

  class Backend($: BackendScope[Props, State]) {

    def handleOnPlayVideo(filename: String)(e: ReactEvent): Callback = {
      {
        Callback(DrtApi.post(s"viewed-video/$filename", write("")))
      }
    }

    def render(props: Props, state: State) = {

      val carouselItems =
        ThemeProvider(DrtTheme.theme)(
          MuiDialog(open = props.showDialog, maxWidth = "lg", fullWidth = true)(
            <.div(
              MuiGrid(container = true, spacing = 2, sx = SxProps(Map(
                "backgroundColor" -> DrtTheme.theme.palette.primary.`50`,
              )))(
                MuiGrid(item = true, xs = 10)(
                  MuiDialogTitle(sx = SxProps(Map(
                    "color" -> DrtTheme.theme.palette.primary.`700`,
                    "font-family" -> "Arial",
                    "font-size" -> "40px",
                    "font-weight" -> "bold"
                  )))(<.span(s"New features available for DRT"))),
                MuiGrid(item = true, xs = 2)(
                  MuiDialogActions()(
                    MuiIconButton(color = Color.primary)(^.onClick ==> props.closeDialog, ^.aria.label := "Close")(
                      Icon.close))),
              )),
            MuiDialogContent(sx = SxProps(Map(
              "backgroundColor" -> DrtTheme.theme.palette.primary.`50`,
              "padding" -> "16px",
            )))(
              Flickity()(props.trainingDataTemplates.map { data =>
                MuiGrid(container = true, spacing = 2)(
                  MuiGrid(item = true, xs = 8, sx = SxProps(Map(
                    "backgroundColor" -> "#FFFFFF",
                    "border" -> "16px solid #C0C7DE"
                  )))(
                    <.video(VdomAttr("src") := SPAMain.absoluteUrl(s"training-video/${data.fileName.getOrElse("")}"),
                      VdomAttr("autoPlay") := false,
                      VdomAttr("controls") := true,
                      VdomAttr("width") := "100%",
                      VdomAttr("height") := "100%",
                      ^.onPlay ==> handleOnPlayVideo(data.fileName.getOrElse("")))),
                  MuiGrid(item = true, xs = 4, sx = SxProps(Map(
                    "backgroundColor" -> "#FFFFFF",
                    "padding" -> "16px",
                    "border-top" -> "16px solid #C0C7DE",
                    "border-right" -> "16px solid #C0C7DE",
                    "border-bottom" -> "16px solid #C0C7DE",
                    "border-left" -> "0px solid #C0C7DE",
                    "font-family" -> "Arial",
                  )))(
                    MuiGrid(container = true, spacing = 2)(
                      MuiGrid(item = true, xs = 12, sx = SxProps(Map(
                        "font-family" -> "Arial",
                        "font-size" -> "28px",
                        "font-weight" -> "bold",
                        "padding-bottom" -> "16px",
                      )))(<.span(data.title)),
                      MuiGrid(item = true, xs = 12, sx = SxProps(Map(
                        "font-family" -> "Arial",
                        "font-size" -> "16px"
                      )))(TagMod(data.markdownContent.replaceAll("\r", " ").split("\n").map(<.div(_)): _*))
                    )
                  ))
              })
            )))
      <.div(carouselItems)
    }
  }

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("NavBar")
    .initialStateFromProps(_ => State(1))
    .renderBackend[Backend]
    .componentDidMount(_ => Callback(GoogleEventTracker.sendPageView("training-data")))
    .build


  def apply(showDialog: Boolean, closeDialog: ReactEvent => Callback,
    trainingDataTemplates: Seq[TrainingData]): VdomElement = component(Props(showDialog, closeDialog, trainingDataTemplates))

}
