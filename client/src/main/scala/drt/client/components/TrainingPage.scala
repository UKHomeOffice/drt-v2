package drt.client.components

import drt.client.SPAMain
import drt.client.components.styles.WithScalaCssImplicits
import drt.client.modules.GoogleEventTracker
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{BackendScope, Callback, CtorType, ReactEvent, ScalaComponent}
import uk.gov.homeoffice.drt.training.TrainingData

import scala.language.postfixOps


object TrainingModalComponent extends WithScalaCssImplicits {
  case class State(currentStep: Double)

  case class Props(showDialog: Boolean, closeDialog: ReactEvent => Callback, trainingDataTemplates: Seq[TrainingData])

  class Backend($: BackendScope[Props, State]) {

    def render(props: Props, state: State) = {
      val carouselItems =
        MuiDialog(open = props.showDialog, maxWidth = "lg", fullWidth= true)(
          MuiDialogTitle()(<.p(s"New Features")),
          MuiDialogContent()(
              Flickity()(props.trainingDataTemplates.map { data =>
              MuiGrid(container = true, spacing = 2)(
                MuiGrid(item = true, xs = 12)(
                  <.div(^.className := "training-grid-item", <.p(data.title))),
                  MuiGrid(item = true, xs = 8)(
                    <.video(VdomAttr("src") := SPAMain.absoluteUrl(s"training-video/${data.fileName.getOrElse("")}"), VdomAttr("autoPlay") := false,
                      VdomAttr("controls") := true, VdomAttr("width") := "100%", VdomAttr("height") := "100%")),
                  MuiGrid(item = true, xs = 4)(
                    <.div(^.className := "training-grid-item", data.markdownContent))
                )
            })
          ),
          MuiDialogActions()(
            MuiButton(color = Color.primary, variant = "outlined", size = "medium")
            ("Cancel", ^.onClick ==> props.closeDialog))
        )
      <.div(^.className := "training-modal", carouselItems)
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
