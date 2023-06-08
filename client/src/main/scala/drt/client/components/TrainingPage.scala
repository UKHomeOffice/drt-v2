package drt.client.components

import diode.UseValueEq
import drt.client.services.SPACircuit
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, VdomTagOf, ^, _}
import japgolly.scalajs.react.{CtorType, ScalaComponent}

object TrainingModalComponent {
  case class State(showDialogue: Boolean, count: Int) extends UseValueEq

  case class Props()


  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ContactUs")
    .initialStateFromProps(p => State(showDialogue = false, count = 0))
    .renderS((scope, state) => {
      //      val contactDetailsRCP = SPACircuit.connect(m => ContactModel(m.contactDetails, m.oohStatus))
      //      <.div(
      //        contactDetailsRCP(contactDetailsMP => {
      //          <.div(
      //            contactDetailsMP().contactDetails.renderReady(details => {
      //              contactDetailsMP().oohStatus.renderReady(oohStatus => {


      def newFeatureDialog =
        MuiDialog(open = state.showDialogue, maxWidth = "lg")(
          MuiDialogTitle()(
            <.h4(s"New Features")
          ),
          MuiDialogContent()(
            MuiGrid(container = true, spacing = 2)(
              MuiGrid(item = true, xs = 8)(
                <.div(^.className := "training-grid-item" ,
                <.video(VdomAttr("src") := "/assets/feature1.webm", VdomAttr("autoPlay") := true,
                  VdomAttr("controls") := true, VdomAttr("width") := "100%", VdomAttr("height") := "100%"))),
                MuiGrid(item = true, xs = 4)(
                  <.div(^.className := "training-grid-item" ,"This is a test video")
                )
            ),
            MuiDialogActions()(
              MuiButton(color = Color.primary, variant = "outlined", size = "medium")("Cancel", ^.onClick --> scope.modState(_.copy(showDialogue = false))),
            )
          )
        )

      <.div(^.className := "contact-us-link",<.a(^.onClick --> scope.modState(_.copy(showDialogue = true, count = state.count + 1)), "New Features"), newFeatureDialog)

      //              })
      //            })
      //          )
      //        })
      //      )

    })
    .build

  def apply(): VdomElement = component(Props())

}
