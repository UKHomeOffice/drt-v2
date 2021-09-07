package drt.client.components

import diode.UseValueEq
import diode.data.Ready
import drt.client.actions.Actions.SetSnackbarMessage
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.{DrtApi, SPACircuit}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, VdomTagOf, ^, _}
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom.html.{Anchor, Div}
import upickle.default.{macroRW, write, ReadWriter => RW}

import scala.scalajs.js

object NegativeFeedbackComponent {
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(url: String,
                   userEmail: String) extends UseValueEq

  case class State(url: String,
                   feedbackUserEmail: String,
                   whatUserDoing: String,
                   whatWentWrong: String,
                   whatToImprove: String,
                   contactMe: Boolean,
                   showDialogue: Boolean) extends UseValueEq

  implicit val rw: RW[State] = macroRW

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FeedbackComponent")
    .initialStateFromProps(p => State(
      url = p.url,
      feedbackUserEmail = p.userEmail,
      whatUserDoing = "",
      whatWentWrong = "",
      whatToImprove = "",
      contactMe = false,
      showDialogue = false,
    ))
    .renderS((scope, state) => {

      val showClass = if (state.showDialogue) "show" else "fade"

      def setWhatUserDoing(whatUserDoing: String) = scope.modState(_.copy(whatUserDoing = whatUserDoing))

      def setWhatWentWrong(whatWentWrong: String) = scope.modState(_.copy(whatWentWrong = whatWentWrong))

      def setWhatToImprove(whatToImprove: String) = scope.modState(_.copy(whatToImprove = whatToImprove))

      def setContactMe(contactMe: Boolean) = scope.modState(_.copy(contactMe = contactMe))

      def negativeFeedbackDialog: VdomTagOf[Div] = {
        <.div(^.className := "modal " + showClass, ^.id := "#negativeFeedback", ^.tabIndex := -1, ^.role := "dialog",
          <.div(
            ^.className := "modal-dialog modal-dialog-centered",
            ^.role := "document",
            <.div(
              ^.className := "modal-content",
              <.div(^.className := "modal-header",
                <.h5(^.className := "modal-title", "Thanks for your feedback. This helps us improve the service.")
              ),
              <.br(),
              <.div(^.className := "modal-body",
                <.div(^.`class` := "row",
                  <.label(^.`for` := "whatUserDoing-text", "What were you doing?", ^.`class` := "col-md-4"),
                  <.textarea(^.id := "whatUserDoing-text", ^.placeholder := "Please write your comments here ...", ^.`class` := "col-md-7", ^.value := state.whatUserDoing, ^.onChange ==> ((e: ReactEventFromInput) => setWhatUserDoing(e.target.value))),
                ),
                <.br(),
                <.div(^.`class` := "row",
                  <.label(^.`for` := "whatWentWrong-text", "What went wrong?", ^.`class` := "col-md-4"),
                  <.textarea(^.id := "whatWentWrong-text", ^.placeholder := "Please write your comments here ...", ^.`class` := "col-md-7", ^.value := state.whatWentWrong, ^.onChange ==> ((e: ReactEventFromInput) => setWhatWentWrong(e.target.value))),
                ),
                <.br(),
                <.div(^.`class` := "row",
                  <.label(^.`for` := "whatToImprove-text", "What can we improve?", ^.`class` := "col-md-4"),
                  <.textarea(^.id := "whatToImprove-text", ^.placeholder := "Please write your suggestion here ....", ^.`class` := "col-md-7", ^.value := state.whatToImprove, ^.onChange ==> ((e: ReactEventFromInput) => setWhatToImprove(e.target.value))),
                ),
                <.br(),
                <.div(^.`class` := "row",
                  <.div(^.`class` := "col-md-10 ml-auto",
                    <.ul(^.className := "nav navbar-nav navbar-left",
                      <.li(<.input.checkbox(^.checked := state.contactMe, ^.onChange ==> ((e: ReactEventFromInput) => setContactMe(e.target.checked)), ^.id := "check-contactMe")),
                      <.li(<.label(^.style := js.Dictionary("marginLeft" -> "5px"), ^.`for` := "check-contactMe", "Are you happy for us to contact you regarding your feedback?"))
                    ))
                )
              ),
              <.br(),
              <.div(^.className := "modal-footer",
                <.div(
                  <.div(^.className := "feedback-links",
                    <.button("Submit", ^.className := "btn btn-default",
                      ^.onClick --> {
                        scope.modState(
                          _.copy(showDialogue = false),
                          Callback(DrtApi.post("email/feedback/negative", write(state))) >>
                            Callback(SPACircuit.dispatch(SetSnackbarMessage(Ready("Thanks for your feedback. This helps us improve the service.")))))
                      }
                    ),
                    <.button(^.className := "btn btn-link",
                      VdomAttr("data-dismiss") := "modal", "Close",
                      ^.onClick --> scope.modState(_.copy(showDialogue = false))
                    )
                  )
                )
              )
            )
          )
        )
      }

      def feedBackButton(icon: Icon.Icon, target: String, btnColor: String): VdomTagOf[Anchor] = {
        <.a(
          icon,
          ^.className := s"btn btn-default $btnColor",
          VdomAttr("data-toggle") := "modal",
          VdomAttr("data-target") := s"#$target",
          ^.onClick --> scope.modState(_.copy(showDialogue = true)),
          ^.aria.label := "Negative feedback",
          ^.tabIndex := 0
        )
      }

      <.div(
        feedBackButton(Icon.thumbsODown, "negativeFeedback", "btn-danger"),
        negativeFeedbackDialog())

    })
    .build


  def apply(url: String, userEmail: String): VdomElement = component(Props(url, userEmail))
}
