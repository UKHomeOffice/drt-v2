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
import upickle.default.write

import io.kinoplan.scalajs.react.material.ui.core.{MuiDialog, MuiDialogContent, MuiDialogTitle}
import uk.gov.homeoffice.drt.training.FeatureGuide
import scala.scalajs.js

object FeatureGuideModalComponent extends WithScalaCssImplicits {

  case class State(currentStep: Double)

  case class Props(showDialog: Boolean, closeDialog: ReactEvent => Callback, trainingDataTemplates: Seq[FeatureGuide])

  class Backend($: BackendScope[Props, State]) {

    def recordFeatureGuideView(filename: String)(e: ReactEvent): Callback = {
      {
        Callback(DrtApi.post(s"record-feature-guide-view/$filename", write("")))
      }
    }

    def render(props: Props, state: State) = {

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
                  )))(<.span(s"New features available for DRT"))),
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
              "padding-bottom" -> "64px",
              "overflow" -> "hidden"
            )))(Flickity()(props.trainingDataTemplates.map { data =>
              MuiGrid(container = true, spacing = 2)(
                MuiGrid(item = true, xs = 8, sx = SxProps(Map(
                  "backgroundColor" -> "#C0C7DE",
                  "border" -> "16px solid #C0C7DE"
                )))(
                  if (data.fileName.exists(_.contains("webm"))) {
                    <.video(VdomAttr("src") := SPAMain.absoluteUrl(s"feature-guide-video/${data.fileName.getOrElse("")}"),
                      VdomAttr("autoPlay") := false,
                      VdomAttr("controls") := true,
                      VdomAttr("width") := "100%",
                      VdomAttr("height") := "100%",
                      ^.onPlay ==> recordFeatureGuideView(data.fileName.getOrElse("")))
                  } else {
                    <.img(VdomAttr("src") := SPAMain.absoluteUrl(s"feature-guide-video/${data.fileName.getOrElse("")}"),
                      VdomAttr("width") := "100%",
                      VdomAttr("height") := "100%",
                      ^.onLoad ==> recordFeatureGuideView(data.fileName.getOrElse("")))
                  }
                ),
                MuiGrid(item = true, xs = 4, sx = SxProps(Map(
                  "backgroundColor" -> "#FFFFFF",
                  "margin-top" -> "16px",
                  "border-top" -> "16px solid #C0C7DE",
                  "border-right" -> "16px solid #C0C7DE",
                  "border-bottom" -> "16px solid #C0C7DE",
                  "border-left" -> "0px solid #C0C7DE",
                )))(
                  <.div(^.style := js.Dictionary("height" -> "400px", "overflow" -> "auto"),
                    <.div(^.style := js.Dictionary(
                      "font-size" -> DrtTheme.theme.typography.h3.fontSize,
                      "font-weight" -> DrtTheme.theme.typography.h3.fontWeight,
                      "padding-right" -> "16px"),
                      <.span(data.title)),
                    <.div(^.style := js.Dictionary(
                      "padding-top" -> "16px",
                      "padding-right" -> "16px",
                      "padding-bottom" -> "16px"),
                      Markdown(data.markdownContent)),
                  )))
            })
            )))
      carouselItems
    }
  }

  val component: Component[Props, State, Backend, CtorType.Props] =
    ScalaComponent
      .builder[Props]("NavBar")
      .initialStateFromProps(_ => State(1))
      .renderBackend[Backend]
      .componentDidMount(_ => Callback(GoogleEventTracker.sendPageView("feature-guide")))
      .build

  def apply(showDialog: Boolean,
            closeDialog: ReactEvent => Callback,
            trainingDataTemplates: Seq[FeatureGuide]): VdomElement =
    component(Props(showDialog, closeDialog, trainingDataTemplates))

}